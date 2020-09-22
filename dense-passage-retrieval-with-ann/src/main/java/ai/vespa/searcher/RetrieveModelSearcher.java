package ai.vespa.searcher;

import ai.vespa.tokenizer.BertTokenizer;
import com.google.inject.Inject;
import com.yahoo.component.chain.dependencies.Before;
import com.yahoo.prelude.query.*;
import com.yahoo.processing.request.CompoundName;
import com.yahoo.search.Query;
import com.yahoo.search.Result;
import com.yahoo.search.Searcher;
import com.yahoo.search.result.ErrorMessage;
import com.yahoo.search.result.FeatureData;
import com.yahoo.search.searchchain.Execution;
import com.yahoo.tensor.Tensor;
import com.yahoo.tensor.functions.Reduce;


import java.util.List;
import java.util.Optional;

@Before("QuestionAnswering")
public class RetrieveModelSearcher extends Searcher {

    private static String QUERY_TENSOR_NAME = "query(query_token_ids)";
    private static String QUERY_EMBEDDING_TENSOR_NAME = "query(query_embedding)";
    private static CompoundName model = new CompoundName("retriever");

    BertTokenizer tokenizer;

    @Inject
    public RetrieveModelSearcher(BertTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    @Override
    public Result search(Query query, Execution execution) {
        String queryInput = query.getModel().getQueryString();
        if(query.getModel().getQueryString() == null ||
                query.getModel().getQueryString().length() == 0)
            return new Result(query, ErrorMessage.createBadRequest("No query input"));

        Tensor questionTokenIds = getQueryTokenIds(queryInput, 256);
        query.getRanking().getFeatures().put(QUERY_TENSOR_NAME, questionTokenIds);
        String retrievalModel = query.properties().getString(model, "dense");

        if(retrievalModel.equals("dense")) {
            Optional<Tensor> inputTensor = query.getRanking().getFeatures().getTensor(QUERY_EMBEDDING_TENSOR_NAME);
            Tensor questionEmbedding;
            if(inputTensor.isEmpty()) {
                questionEmbedding = getEmbeddingTensor(questionTokenIds, execution, query);
                query.getModel().getQueryTree().setRoot(denseRetrieval(questionEmbedding, query));
            }
            else {
                questionEmbedding = inputTensor.get(); //TODO clean up
                NearestNeighborItem nn = new NearestNeighborItem("text_embedding", "query_embedding");
                nn.setTargetNumHits(query.getHits());
                nn.setAllowApproximate(true);
                nn.setHnswExploreAdditionalHits(query.properties().getInteger("ann.extra",1000));
                query.getRanking().getFeatures().put(QUERY_EMBEDDING_TENSOR_NAME , questionEmbedding);
                query.getModel().getQueryTree().setRoot(nn);
            }
            query.getRanking().setProfile("dense");
        } else if (retrievalModel.equals("sparse")){
            query.getModel().getQueryTree().setRoot(sparseRetrieval(queryInput, query));
            query.getRanking().setProfile("sparse");
        } else {
            Tensor questionEmbedding = getEmbeddingTensor(questionTokenIds, execution, query);
            Item ann = denseRetrieval(questionEmbedding,query);
            Item wand = sparseRetrieval(queryInput,query);
            OrItem disjunction = new OrItem();
            disjunction.addItem(ann);
            disjunction.addItem(wand);
            query.getModel().getQueryTree().setRoot(disjunction);
            query.getRanking().setProfile("hybrid");
        }
        query.getModel().setRestrict("wiki");
        return execution.search(query);
    }

    private Item sparseRetrieval(String queryInput, Query query) {
        String[] tokens = queryInput.split(" ");
        WeakAndItem wand = new WeakAndItem();
        wand.setN(query.getHits());
        for(String t: tokens)
            wand.addItem(new WordItem(t,"default", true));
        return wand;
    }

    private Item denseRetrieval(Tensor questionEmbedding, Query query) {
        NearestNeighborItem nn = new NearestNeighborItem("text_embedding", "query_embedding");
        nn.setTargetNumHits(query.getHits());
        nn.setAllowApproximate(true);
        nn.setHnswExploreAdditionalHits(query.properties().getInteger("ann.extra",1000));
        Tensor rewritten = rewriteQueryTensor(questionEmbedding);
        query.getRanking().getFeatures().put(QUERY_EMBEDDING_TENSOR_NAME , rewritten);
        return nn;
    }


    private Tensor rewriteQueryTensor(Tensor embedding) {
        return embedding.reduce(Reduce.Aggregator.min, "d0").concat(0, "d1").rename("d1", "x");
    }

    private Tensor getEmbeddingTensor(Tensor questionTensor, Execution execution, Query query) {
        Query embeddingModelQuery = new Query();
        query.attachContext(embeddingModelQuery);
        embeddingModelQuery.setHits(1);
        embeddingModelQuery.getModel().setRestrict("query");
        embeddingModelQuery.getRanking().setProfile("question_encoder");
        embeddingModelQuery.getModel().getQueryTree().setRoot(new WordItem("query","sddocname"));
        embeddingModelQuery.getRanking().getFeatures().put(QUERY_TENSOR_NAME, questionTensor);
        Result embeddingResult = execution.search(embeddingModelQuery);
        execution.fill(embeddingResult);
        FeatureData featureData = (FeatureData)embeddingResult.hits().get(0).getField("summaryfeatures");
        return featureData.getTensor("onnxModel(files_encoder_onnx).output_0");
    }

    private Tensor getQueryTokenIds(String queryInput, int maxLength) {
        List<Integer> tokens_ids = tokenizer.tokenize(queryInput, maxLength,true);
        String tensorSpec = "tensor<float>(d0[" + maxLength + "]):" +  tokens_ids;
        return Tensor.from(tensorSpec);
    }

}

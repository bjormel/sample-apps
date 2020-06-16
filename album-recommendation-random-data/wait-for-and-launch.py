import requests
from requests.adapters import HTTPAdapter
from requests.packages.urllib3.util.retry import Retry
import argparse
import subprocess
import time

parser = argparse.ArgumentParser()

parser.add_argument("url")

args = parser.parse_args()

session = requests.Session()
retry = Retry(connect=10, backoff_factor=1.0)
adapter = HTTPAdapter(max_retries=retry)
session.mount("http://", adapter)

try:
    response = session.get(args.url)
    if (response.status_code == 200):
        print("Successful response. Initiating deploy")
except (ConnectionRefusedError, ConnectionError) as e:
    print("Request Denied. Trying again in 5 seconds")        

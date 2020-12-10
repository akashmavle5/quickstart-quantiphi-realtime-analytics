''' code snippet for keyword extraction on live calls transcibed output '''

#packagesss
import pandas
import datetime
import json
import boto3
import os
from urllib.parse import unquote_plus
import logging
from decimal import Decimal
from boto3.dynamodb.conditions import Key, Attr

# AWS clients
s3_client = boto3.client('s3')
dynamodb = boto3.resource('dynamodb')

logger = logging.getLogger()
logger.setLevel(logging.INFO)

# Environment variables
transcripts_table_name = os.environ['TRANSCRIPTS_TABLE']
connections_table_name = os.environ['CONNECTIONS_TABLE']
text_bucket = os.environ['BUCKET']
text_key = os.environ['TEXT_KEY']
download_txt_path = os.environ['DOWNLOAD_TXT_PATH']

def download_file(bucket, key, download_path):
    '''
    Download file from S3 
    '''
    s3_client.download_file(bucket, key, download_path)
    
def convert_time_stamp(n):
    """ Function to help convert timestamps from s to H:M:S """
    ts = datetime.timedelta(seconds=float(n))
    ts = ts - datetime.timedelta(microseconds=ts.microseconds)
    to_dt = datetime.datetime.strptime(str(ts), "%H:%M:%S")
    from_dt = to_dt.strftime("%M:%S")
    # from_dt = to_dt.strftime("%H:%M:%S")
    return from_dt

def decode_transcript(body):
    """Decode the transcript"""
    # define punctuation
    punc = '''!()-[]{};:'"\,<>./?@#$%^&*_~'''
    kword_path = download_txt_path
    # Assign data to variable
    data = body
    keywords = pandas.read_csv(kword_path, sep = "\n", header = None)
    keys = {i.strip():i.strip().lower() for i in keywords[0]}
    def get_key(val): 
        for key, value in keys.items(): 
             if val == value: 
                 return key

    #keys 
    decoded_data = {"StartTime": [], "EndTime": [], "Speaker": [], "Transcript": [], "KeywordPresence": [], "Keywords": []}
    # If speaker identification
    if "Speaker" in data.keys():
        decoded_data["StartTime"].append(convert_time_stamp(data["StartTime"]))
        decoded_data["EndTime"].append(convert_time_stamp(data["EndTime"]))
        decoded_data["Speaker"].append(data["Speaker"])
        decoded_data["Transcript"].append(data["Transcript"])
        # For each word in the segment...
        
        seg = data['Transcript'].lower()
        for ele in punc:
            if ele in punc:
                seg = seg.replace(ele, '')
                
        kwrds = list(filter(lambda x: seg.find(x) != -1, [' '+i for i in keys.values()]))
        kwrds = [j.strip() for j in kwrds]
        kwrds = [get_key(k) for k in kwrds]

        if kwrds == []:
            decoded_data["KeywordPresence"].append(False)
        else:
            decoded_data["KeywordPresence"].append(True)
        decoded_data["Keywords"].append(kwrds)    

    return decoded_data
    
    
def publish_results(decoded_data,data):
    '''
    Publish results to dynamodb and websocket
    ''' 
    try:
        transcripts_table = dynamodb.Table(transcripts_table_name)
        connections_table = dynamodb.Table(connections_table_name)
        start_time = decoded_data["StartTime"][0]
        end_time = decoded_data["EndTime"][0]
        speaker = decoded_data["Speaker"][0]
        comment = decoded_data["Transcript"][0]
        is_keyword = decoded_data["KeywordPresence"][0]
        keywords = decoded_data["Keywords"]
        transaction_id = data["TransactionId"]
        time_ = datetime.datetime.now().strftime('%H:%M:%S') 
        transcripts_table.put_item(
            Item = {
                'TransactionId': transaction_id,
                'StartTime': start_time,
                'EndTime': end_time,
                'Speaker': speaker,
                'Transcript': comment,
                'KeywordPresence': is_keyword,
                'Keywords': keywords,
                'LoggedOn': time_
            }
        )
        connection_id=getConnectionId(transaction_id,connections_table)
        wsclient = boto3.client('apigatewaymanagementapi',endpoint_url = os.environ['WEBSOCKET_URL'])
        response = wsclient.post_to_connection(
            Data=json.dumps({"TransactionId": transaction_id, "StartTime": str(start_time), "EndTime": str(end_time), "Speaker": speaker,"Transcript": comment, "KeywordPresence": is_keyword, "Keywords": keywords}),
            ConnectionId=connection_id)
        
    except Exception as e:
        print('Exception while sending to websocket & dynamodb',str(e))
    
    
def getConnectionId(transaction_id,connections_table):
    '''
    Fetch ConnectionId wrt TransactionId
    ''' 
    try:
        response=connections_table.scan(
            FilterExpression=Attr("TransactionId").eq(transaction_id),
            ProjectionExpression='ConnectionId')
        return response['Items'][0]['ConnectionId']
    except Exception as e:
        print ("Error fetching connection id - ", str(e))
        return ''
  
def lambda_handler(event, context):
    '''
    This function is called when the trancription results are sent from the transcriber lambda via sqs queue 
    ''' 
    logger.info(f"event is {event}")

    if 'key' not in event.keys():
    
        body = json.loads(event['Records'][0]['body'])
        logger.info(f"SQS message body: {body}")
        download_file(text_bucket, text_key, download_txt_path)
        decoded_data = decode_transcript(body)
        publish_results(decoded_data,body)
        
    else:
        return 0
            
    return {
        'statusCode': 200,
        'body': json.dumps('Hello from Lambda!')
    }    
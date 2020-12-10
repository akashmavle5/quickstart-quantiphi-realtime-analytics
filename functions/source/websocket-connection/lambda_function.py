
#packages
import json
import logging
import os
import boto3
from botocore.exceptions import ClientError
from boto3.dynamodb.conditions import Key, Attr

# Set up logging
logging.basicConfig(format='%(levelname)s: %(asctime)s: %(message)s')
logger = logging.getLogger()
logger.setLevel(logging.INFO)

# make connection to s3
s3 = boto3.resource("s3")
s3_client = boto3.client("s3")

# make the connection to dynamodb
dynamodb = boto3.resource('dynamodb')

# select the table
table = dynamodb.Table(os.environ['CONNECTIONS_TABLE'])
transcripts_table = dynamodb.Table(os.environ['TRANSCRIPTS_TABLE'])
keywords_table = dynamodb.Table(os.environ['KEYWORDS_TABLE'])
entities_table = dynamodb.Table(os.environ['ENTITIES_TABLE'])

def get_s3_keys(bucket, prefix):
    '''
    Get individual audio files of both caller and callee from s3
    '''
    keys = []

    kwargs = {'Bucket': bucket,'Prefix' : prefix}
    while True:
        resp = s3_client.list_objects_v2(**kwargs)
        for obj in resp['Contents']:
            key = obj['Key']
            # print(key)
            extension = key.split('.')[-1]
            if extension == 'wav':
                keys.append(key)
        try:
            kwargs['ContinuationToken'] = resp['NextContinuationToken']
        except KeyError:
            break
    return keys

def delete_from_s3(bucket,transactionId):
    '''
    Delete audio files from s3 buckets
    '''
    prefix = f'voiceConnectorToKVS_{transactionId}_'
    keys=get_s3_keys(bucket, prefix)
    for key in keys:
        obj = s3.Object(bucket, key)
        obj.delete()
    logger.info("deleted objects")

# db apis

def delete_from_transcripts_table(transactionId):
    '''
    Delete records from transcripts_table
    '''
    items = transcripts_table.query(KeyConditionExpression=Key("TransactionId").eq(transactionId))
    response = items['Items']
    for item in response:
        transcripts_table.delete_item(
                    Key={
                        'TransactionId': transactionId,
                        'StartTime': item['StartTime']
                    }
                    
                )

def delete_from_keywords_table(transactionId):
    '''
    Delete records from transcripts_table
    '''
    items = keywords_table.query(KeyConditionExpression=Key("TransactionId").eq(transactionId))
    response = items['Items']
    for item in response:
        keywords_table.delete_item(
                    Key={
                        'TransactionId': transactionId,
                        'StartTime': item['StartTime']
                    }
                    
                )
            
def delete_from_entities_table(transactionId):
    '''
    Delete record from entities_table
    '''
    entities_table.delete_item(
            Key={
                'TransactionId': transactionId
            }
        )
            
def update_table(connectionId,transactionId):
    '''
    Update connections_table
    '''
    table.update_item(
            Key={'ConnectionId': connectionId}, 
            UpdateExpression = 'SET TransactionId = :value1', 
            ExpressionAttributeValues = {':value1': transactionId})

def insert_to_table(connectionId):
    '''
    Insert new record into connections_table
    '''
    table.put_item(
        Item = {
            'ConnectionId': connectionId
        }
    )
    
def delete_from_table(connectionId):
    '''
    Delete record from connections_table
    '''
    table.delete_item(
            Key={
                'ConnectionId': connectionId
            }
        )
        

def lambda_handler(event, context):
    """

    The function is called when the route selection expression
    $request.body.service matches "$connect" or "$disconnect" or "transcribe":

    """

    # Log the values received in the event and context arguments
    logger.info('message event: ' + json.dumps(event, indent=2))
    routeKey = event['requestContext']['routeKey']
    connectionId = event['requestContext']['connectionId']
    if routeKey == '$connect':
        # if client is registered to the connection, connection id is inserted into the table
        insert_to_table(connectionId)
        msg = 'connection established'
    elif routeKey == 'transcribe':
        # receive transactionId from the message body and set transactionId against connectionId in the table
        body = json.loads(event['body'])
        transactionId = body.get('transactionId')
        update_table(connectionId,transactionId)
        msg = 'sent transcribe message'
    elif routeKey == '$disconnect':
        # if client is disconnected, delete the record from the table
        delete_from_table(connectionId)
        msg = 'disconnected'
    elif routeKey == '$default':
        # if client is on default route
        msg = 'sent default message'
        wsclient = boto3.client('apigatewaymanagementapi',endpoint_url = os.environ['WEBSOCKET_URL'])
        response = wsclient.post_to_connection(
            Data=json.dumps(msg),
            ConnectionId=connectionId
        )
    elif routeKey == 'delete':
        # if client is on delete route
        msg = 'deleted files from s3 and records from tables'
        body = json.loads(event['body'])
        transactionId = body.get('transactionId')
        single_files_bucket = os.environ["SINGLE_FILES_S3_BUCKET"]
        merged_files_bucket = os.environ["MERGED_FILES_S3_BUCKET"]
        # transactionId = '0da9e59b-c092-44f7-976d-04fde7e9fd82'
        try:
            delete_from_transcripts_table(transactionId)
            delete_from_keywords_table(transactionId)
            delete_from_entities_table(transactionId)
        except Exception as e:
            print("Exception while deleting DynanmoDB .",str(e))
        try:
            delete_from_s3(single_files_bucket,transactionId)
            delete_from_s3(merged_files_bucket,transactionId)
        except Exception as e:
            print("Exception while deleting S3 files.",str(e))
        wsclient = boto3.client('apigatewaymanagementapi',endpoint_url = os.environ['WEBSOCKET_URL'])
        response = wsclient.post_to_connection(
            Data=json.dumps(msg),
            ConnectionId=connectionId
        )
    return {
        'statusCode': 200,
        'body': json.dumps(msg)
    }

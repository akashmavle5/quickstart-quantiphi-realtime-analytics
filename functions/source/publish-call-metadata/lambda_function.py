''' code snippet to 1. publish metadata of a particular call to websocket and dynamodb
                    2. extract custom entities from the whole transcript at the end of the call
'''

#packages
import json
import logging
import boto3
import os
import datetime
from boto3.dynamodb.conditions import Key, Attr
import numpy as np
import re
from date_extractor import extract_dates
import time
from pydub import AudioSegment

#setting up logging
logger = logging.getLogger()
logger.setLevel(logging.INFO)

# make the connection to dynamodb
dynamodb = boto3.resource('dynamodb')

region = os.environ['AWS_REGION']
# make the connection to comprehend
client = boto3.client('comprehend', region_name=region)

# make the connection to s3
s3_client = boto3.client('s3')

# select the table
table = dynamodb.Table(os.environ['METADATA_TABLE'])
connections_table = dynamodb.Table(os.environ['CONNECTIONS_TABLE'])
transcripts_table = dynamodb.Table(os.environ['TRANSCRIPTS_TABLE'])

def update_table_with_merged_s3_path(key,transactionId):
    '''
    update the table
    '''
    table.update_item(
            Key={'TransactionId': transactionId}, 
            UpdateExpression = 'SET Filename = :value1', 
            ExpressionAttributeValues = {':value1': key})

def merge_audios(bucket,key1,key2):
    '''
    Merge caller and callee audio files and upload to s3
    '''
    download_path_1 = f"/tmp/{key1}"
    s3_client.download_file(bucket, key1, download_path_1)
    download_path_2 = f"/tmp/{key2}"
    s3_client.download_file(bucket, key2, download_path_2)
    sound1 = AudioSegment.from_file(download_path_1)
    sound2 = AudioSegment.from_file(download_path_2)
    combined = sound1.overlay(sound2)
    output_file_path=download_path_1.split(" ")[0]+'.wav'
    combined.export(output_file_path, format='wav')
    s3_path = output_file_path.split('/')[-1]
    s3_client.upload_file(output_file_path, os.environ['MERGED_FILES_S3_BUCKET'] ,s3_path,ExtraArgs={'ContentType': 'audio/wav','ACL':'private'})
    return s3_path
    
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
            extension = key.split('.')[-1]
            if extension == 'wav':
                keys.append(key)
        try:
            kwargs['ContinuationToken'] = resp['NextContinuationToken']
        except KeyError:
            break
    return keys

def publish_to_websocket(transactionId,fromNumber,toNumber,streamingStatus,date_,entities):
    ''' 
        Publish transaction details and extraction results to websocket
    '''
    try:
        print("payload", json.dumps({"transactionId": transactionId, "fromNumber": fromNumber, "toNumber": toNumber, "streamingStatus": streamingStatus,"date_": date_,"customEntities": entities}))
        response = connections_table.scan()
        data_ = response['Items']
        for item in data_:
            wsclient = boto3.client('apigatewaymanagementapi',endpoint_url = os.environ['WEBSOCKET_URL'])
            response = wsclient.post_to_connection(
                Data=json.dumps({"transactionId": transactionId, "fromNumber": fromNumber, "toNumber": toNumber, "streamingStatus": streamingStatus,"date_": date_,"customEntities": entities}),
                ConnectionId=item['ConnectionId']
            )
            
    except Exception as e:
        print("Exception while sending to websocket",str(e))

def insert_into_table(receivedEventAt,transactionId,fromNumber,toNumber,streamingStatus,date_,time_,count):
    ''' 
        Publish transaction details and extraction results to dynamodb
    '''
    if streamingStatus == 'STARTED' and count==0:
        entities={"MemberInfo": [], "ProviderInfo": []}
        publish_to_websocket(transactionId,fromNumber,toNumber,streamingStatus,date_,entities)
        table.put_item(
        Item = {
            'TransactionId': transactionId,
            'LoggedEventOn': receivedEventAt,
            'CallStartTime': time_,
            'FromNumber': fromNumber,
            'ToNumber': toNumber,
            'StreamingStatus': streamingStatus,
            'Date': date_
        }
    )
    if streamingStatus == 'ENDED':
        entities={"memberInfo": [], "providerInfo": []}
        time.sleep(4)
        member_name,member_ID,phone_number,dob=custom_entity_extraction(transactionId)
        member_info = {}
        provider_info = {}
        member_info["memberName"]=member_name
        member_info["memberIDNumber"]=member_ID
        member_info["callbackPhoneNumber"]=phone_number
        member_info["DOB"]=dob
        entities["memberInfo"].append(member_info)
        entities["providerInfo"].append(provider_info)
        publish_to_websocket(transactionId,fromNumber,toNumber,streamingStatus,date_,entities)
        table.update_item(
            Key={'TransactionId': transactionId}, 
            UpdateExpression = 'SET StreamingStatus = :value1, customEntities = :value2', 
            ExpressionAttributeValues = {
                ':value1': streamingStatus,
                ':value2': entities
            })
        # time.sleep(2)
        prefix = f'voiceConnectorToKVS_{transactionId}_'
        keys=get_s3_keys(os.environ['SINGLE_FILES_S3_BUCKET'], prefix)
        s3_path=merge_audios(os.environ['SINGLE_FILES_S3_BUCKET'],keys[0],keys[1])
        update_table_with_merged_s3_path(s3_path,transactionId)
        
    else:
        return
    

def exists(transactionId):
    ''' 
        Check if transactionId is already present in metadata table
    '''
    count = table.query(KeyConditionExpression=Key('TransactionId').eq(transactionId)).get('Count')
    return count
    
def get_transcripts(transactionId):
    ''' 
        get all the transcripts from database 
    '''
    items = transcripts_table.query(KeyConditionExpression=Key("TransactionId").eq(transactionId))
    response = items['Items']
    transcript = ' '.join(i['Transcript'] for i in response)
    return transcript
    
def custom_entity_extraction(transactionId):
    ''' 
        Get the whole transcript and call different enitity functions 
    '''
    try:
        transcript=get_transcripts(transactionId)
        member_name=extract_member_name(transcript)
        member_ID=extract_member_ID(transcript)
        phone_number=extract_phone_number_ctxt(transcript)
        dob=extract_DOB(transcript)
    except Exception as e:
        member_name=''
        member_ID=''
        phone_number=''
        dob=''
        print('Exception in custom entity section',e)
    return member_name,member_ID,phone_number,dob

# ENTITY FUNCTIONS START HERE
 
# MEMBER ID

def extract_member_ID(transcript):
    punc = '''!()-[]{};:'"\,<>./?@#$%^&*_~'''
    for ele in punc:
        if ele in punc:
            transcript = transcript.replace(ele, '')
    transcript = transcript.lower()
    nospace = ''.join(i for i in transcript.split())
    r1 = re.findall(r"[0-9]{6}", nospace)
    
    context = [i.lower() for i in ['memberID', 'membersID', 'insurancecard', 'insuredid', 'patientsID', 'IDnumber']]
    
    if r1 != []:
        for i in r1:
            a = nospace.find(i)
            seg = nospace[np.clip(a - 100, 0, len(nospace)):np.clip(a + 10, 0, len(nospace))]
            key = list(filter(lambda x: x in seg, context))
            if len(key) != 0:
                return i.strip()
                break
        return ''
    else: 
        return ''
        
# PHONE NUMBER

def check_extension(seg, number):
    a = seg.find(number)
    b = 'extension'
    c = seg[a-10:a+len(number)+30]
    if b in c:
        d = c.find(b)
        e = re.findall(r'[0-9]{2,6}', c[d:d+20])[0].strip()
        return number[:-len(e)]+e
    else:
        return False

def extract_phone_number_ctxt(transcript):
    punc = '''!()-[]{};:'"\,<>./?@#$%^&*_~'''
    for ele in punc:
        if ele in punc:
            transcript = transcript.replace(ele, '')
    transcript = transcript.lower()
    nospace = ''.join(i for i in transcript.split())

    r1 = re.findall(r"[0-9]{8,10}", nospace)
    
    context = [i.lower() for i in ['callbacknumber', 'callbackphonenumber', 'phonenumber', 'callback']]

    if r1 != []:
        for i in r1:
            a = nospace.find(i)
            seg = nospace[np.clip(a - 100, 0, len(nospace)):np.clip(a + 10, 0, len(nospace))]
            key = list(filter(lambda x: x in seg, context))
            if len(key) != 0:
                ext = check_extension(nospace, i.strip())
                if ext != False:
                    return ext
                else:
                    return i.strip()
                break
        return ''
    else: 
        return ''

# DOB
        
def extract_DOB(transcript):
    punc = '''!()-[]{};:'"\,<>.?@#$%^&*_~'''
    for ele in punc:
        if ele in punc:
            transcript = transcript.replace(ele, '')
    transcript = transcript.lower() 
    context = [i.lower() for i in ['date of birth', 'DOB', 'birthdate', 'birth date']]
    b = list(filter(lambda k: transcript.find(k) != -1, context))
    if b != []:
        c = transcript.find(b[0])
        text = transcript[np.clip(c - 50, 0, len(transcript)):np.clip(c + 100, 0, len(transcript))]
        text = text.replace('of', '').replace('  ', ' ')
        try: 
            r1 = re.findall(r"\s[0-9]{1,2}[a-z]{2}\s", text)[0].strip()
            r = re.findall(r"[0-9]{1,2}", r1)[0]
            text = text.replace(r1, r)
            dob = extract_dates(text)
        except:
            dob = extract_dates(text)
        
        if len(dob) != 0:
            return str(dob[0].month).zfill(2) + '-' + str(dob[0].day).zfill(2) + '-' + str(dob[0].year)
        else:
            return ''
    else:
        return ''

# MEMBER NAME

def extract_member_name(transcript):
    context = [i.lower() for i in ['patient\'s name', 'patient name', 'member\'s name', 'member name', 'your name', 'last name', 'date of birth']]
    punc = '''!-[]{};:\,<>./@#$%^&*_~'''
    for ele in punc:
        if ele in punc:
            transcript = transcript.replace(ele, '')
    lower = transcript.lower()
    
    b = list(filter(lambda k: lower.find(k) != -1, context))
    print(b)
    if b!= []:
        for j in range(len(b)):
            a = lower.find(b[j])
            seg = transcript[a:np.clip(a + 100, 0, len(transcript))]
            print('seg',seg)
            response = client.detect_entities(Text=seg,LanguageCode='en')
            names = list(filter(lambda x: x['Type'] == 'PERSON', response['Entities']))
            if names != []:
                return names[0]['Text']
                break
            else: return ''
    else:
        return ''
        
# END OF ENTITY FUNCTIONS

def lambda_handler(event, context):
    ''' 
        This function along with transcriber lambda are called when a call is started and ended
    '''
    receivedEventAt=datetime.datetime.now().strftime('%H:%M:%S')
    body = json.loads(event["Records"][0]["body"])
    detail = body.get('detail')
    fromNumber = detail.get('fromNumber')
    toNumber = detail.get('toNumber')
    transactionId = detail.get('transactionId')
    streamingStatus = detail.get('streamingStatus')
    date_ = body.get('time').split('T')[0]
    time_ = body.get('time').split('T')[1].split('Z')[0]
    count=exists(transactionId)
    insert_into_table(receivedEventAt,transactionId,fromNumber,toNumber,streamingStatus,date_,time_,count)
    return {
        'statusCode': 200,
        'body': json.dumps('Hello from Lambda!')
    }
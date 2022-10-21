package com.asynctextract;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.amazonaws.auth.policy.Condition;
import com.amazonaws.auth.policy.Policy;
import com.amazonaws.auth.policy.Principal;
import com.amazonaws.auth.policy.Resource;
import com.amazonaws.auth.policy.Statement;
import com.amazonaws.auth.policy.Statement.Effect;
import com.amazonaws.auth.policy.actions.SQSActions;
import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.AmazonSNSClientBuilder;
import com.amazonaws.services.sns.model.CreateTopicRequest;
import com.amazonaws.services.sns.model.CreateTopicResult;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.QueueAttributeName;
import com.amazonaws.services.sqs.model.SetQueueAttributesRequest;
import com.amazonaws.services.textract.AmazonTextract;
import com.amazonaws.services.textract.AmazonTextractClientBuilder;
import com.amazonaws.services.textract.model.Block;
import com.amazonaws.services.textract.model.DocumentLocation;
import com.amazonaws.services.textract.model.DocumentMetadata;
import com.amazonaws.services.textract.model.GetDocumentAnalysisRequest;
import com.amazonaws.services.textract.model.GetDocumentAnalysisResult;
import com.amazonaws.services.textract.model.GetDocumentTextDetectionRequest;
import com.amazonaws.services.textract.model.GetDocumentTextDetectionResult;
import com.amazonaws.services.textract.model.NotificationChannel;
import com.amazonaws.services.textract.model.Relationship;
import com.amazonaws.services.textract.model.S3Object;
import com.amazonaws.services.textract.model.StartDocumentAnalysisRequest;
import com.amazonaws.services.textract.model.StartDocumentAnalysisResult;
import com.amazonaws.services.textract.model.StartDocumentTextDetectionRequest;
import com.amazonaws.services.textract.model.StartDocumentTextDetectionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.JSONParser;;

public class App {

	private static String sqsQueueName = null;
	private static String snsTopicName = null;
	private static String snsTopicArn = null;
	private static String roleArn = null;
	private static String sqsQueueUrl = null;
	private static String sqsQueueArn = null;
	private static String startJobId = null;
	private static String bucket = null;
	private static String document = null;
	private static AmazonSQS sqs = null;
	private static AmazonSNS sns = null;
	private static AmazonTextract textract = null;

	public enum ProcessType {
		DETECTION, ANALYSIS
	}

	// Creates an SNS topic and SQS queue. The queue is subscribed to the topic.

	public static void main(String[] args) throws Exception {

//		String document = "scotia_statement.pdf";
		String document = "sample1.pdf";
		String bucket = "textract-console-ap-south-1-189c906f-570a-49ba-836a-fed8cb2dd8a";
		String roleArn = "arn:aws:iam::258544224960:role/TextractRole";

		sns = AmazonSNSClientBuilder.defaultClient();
		sqs = AmazonSQSClientBuilder.defaultClient();
		textract = AmazonTextractClientBuilder.defaultClient();

		CreateTopicandQueue();
		ProcessDocument(bucket, document, roleArn, ProcessType.ANALYSIS);
		DeleteTopicandQueue();
		System.out.println("Done!");

	}
	static void CreateTopicandQueue() {
		// create a new SNS topic
		snsTopicName = "AmazonTextractTopic" + Long.toString(System.currentTimeMillis());
		CreateTopicRequest createTopicRequest = new CreateTopicRequest(snsTopicName);
		CreateTopicResult createTopicResult = sns.createTopic(createTopicRequest);
		snsTopicArn = createTopicResult.getTopicArn();

		// Create a new SQS Queue
		sqsQueueName = "AmazonTextractQueue" + Long.toString(System.currentTimeMillis());
		final CreateQueueRequest createQueueRequest = new CreateQueueRequest(sqsQueueName);
		sqsQueueUrl = sqs.createQueue(createQueueRequest).getQueueUrl();
		sqsQueueArn = sqs.getQueueAttributes(sqsQueueUrl, Arrays.asList("QueueArn")).getAttributes().get("QueueArn");

		// Subscribe SQS queue to SNS topic
		String sqsSubscriptionArn = sns.subscribe(snsTopicArn, "sqs", sqsQueueArn).getSubscriptionArn();

		// Authorize queue
		Policy policy = new Policy().withStatements(
				new Statement(Effect.Allow).withPrincipals(Principal.AllUsers).withActions(SQSActions.SendMessage)
						.withResources(new Resource(sqsQueueArn)).withConditions(new Condition().withType("ArnEquals")
								.withConditionKey("aws:SourceArn").withValues(snsTopicArn)));

		Map queueAttributes = new HashMap();
		queueAttributes.put(QueueAttributeName.Policy.toString(), policy.toJson());
		sqs.setQueueAttributes(new SetQueueAttributesRequest(sqsQueueUrl, queueAttributes));

		System.out.println("Topic arn: " + snsTopicArn);
		System.out.println("Queue arn: " + sqsQueueArn);
		System.out.println("Queue url: " + sqsQueueUrl);
		System.out.println("Queue sub arn: " + sqsSubscriptionArn);
	}

	static void DeleteTopicandQueue() {
		if (sqs != null) {
			sqs.deleteQueue(sqsQueueUrl);
			System.out.println("SQS queue deleted");
		}

		if (sns != null) {
			sns.deleteTopic(snsTopicArn);
			System.out.println("SNS topic deleted");
		}
	}

	// Starts the processing of the input document.
	static void ProcessDocument(String inBucket, String inDocument, String inRoleArn, ProcessType type)
			throws Exception {
		bucket = inBucket;
		document = inDocument;
		roleArn = inRoleArn;

		switch (type) {
		case DETECTION:
			StartDocumentTextDetection(bucket, document);
			System.out.println("Processing type: Detection");
			break;
		case ANALYSIS:
			StartDocumentAnalysis(bucket, document);
			System.out.println("Processing type: Analysis");
			break;
		default:
			System.out.println("Invalid processing type. Choose Detection or Analysis");
			throw new Exception("Invalid processing type");

		}

		System.out.println("Waiting for job: " + startJobId);
		// Poll queue for messages
		List<Message> messages = null;
		int dotLine = 0;
		boolean jobFound = false;

		// loop until the job status is published. Ignore other messages in queue.
		do {
			messages = sqs.receiveMessage(sqsQueueUrl).getMessages();
			if (dotLine++ < 40) {
				System.out.print(".");
			} else {
				System.out.println();
				dotLine = 0;
			}

			if (!messages.isEmpty()) {
				// Loop through messages received.
				for (Message message : messages) {
					String notification = message.getBody();

					// Get status and job id from notification.
					ObjectMapper mapper = new ObjectMapper();
					JsonNode jsonMessageTree = mapper.readTree(notification);
					JsonNode messageBodyText = jsonMessageTree.get("Message");
					ObjectMapper operationResultMapper = new ObjectMapper();
					JsonNode jsonResultTree = operationResultMapper.readTree(messageBodyText.textValue());
					JsonNode operationJobId = jsonResultTree.get("JobId");
					JsonNode operationStatus = jsonResultTree.get("Status");
					System.out.println("Job found was " + operationJobId);
					// Found job. Get the results and display.
					if (operationJobId.asText().equals(startJobId)) {
						jobFound = true;
						System.out.println("Job id: " + operationJobId);
						System.out.println("Status : " + operationStatus.toString());
						if (operationStatus.asText().equals("SUCCEEDED")) {
							switch (type) {
							case DETECTION:
								GetDocumentTextDetectionResults();
								break;
							case ANALYSIS:
								GetDocumentAnalysisResults();
								break;
							default:
								System.out.println("Invalid processing type. Choose Detection or Analysis");
								throw new Exception("Invalid processing type");

							}
						} else {
							System.out.println("Document analysis failed");
						}

						sqs.deleteMessage(sqsQueueUrl, message.getReceiptHandle());
					}

					else {
						System.out.println("Job received was not job " + startJobId);
						// Delete unknown message. Consider moving message to dead letter queue
						sqs.deleteMessage(sqsQueueUrl, message.getReceiptHandle());
					}
				}
			} else {
				Thread.sleep(5000);
			}
		} while (!jobFound);

		System.out.println("Finished processing document");
	}

	private static void StartDocumentTextDetection(String bucket, String document) throws Exception {

		// Create notification channel
		NotificationChannel channel = new NotificationChannel().withSNSTopicArn(snsTopicArn).withRoleArn(roleArn);

		StartDocumentTextDetectionRequest req = new StartDocumentTextDetectionRequest()
				.withDocumentLocation(
						new DocumentLocation().withS3Object(new S3Object().withBucket(bucket).withName(document)))
				.withJobTag("DetectingText").withNotificationChannel(channel);

		StartDocumentTextDetectionResult startDocumentTextDetectionResult = textract.startDocumentTextDetection(req);
		startJobId = startDocumentTextDetectionResult.getJobId();
	}

	// Gets the results of processing started by StartDocumentTextDetection
	private static void GetDocumentTextDetectionResults() throws Exception {
		int maxResults = 1000;
		String paginationToken = null;
		GetDocumentTextDetectionResult response = null;
		Boolean finished = false;

		while (finished == false) {
			GetDocumentTextDetectionRequest documentTextDetectionRequest = new GetDocumentTextDetectionRequest()
					.withJobId(startJobId).withMaxResults(maxResults).withNextToken(paginationToken);
			response = textract.getDocumentTextDetection(documentTextDetectionRequest);
			DocumentMetadata documentMetaData = response.getDocumentMetadata();

			System.out.println("Pages: " + documentMetaData.getPages().toString());

			// Show blocks information
			List<Block> blocks = response.getBlocks();
			for (Block block : blocks) {
				DisplayBlockInfo(block);
			}
			paginationToken = response.getNextToken();
			if (paginationToken == null)
				finished = true;

		}

	}

	private static void StartDocumentAnalysis(String bucket, String document) throws Exception {
		// Create notification channel
		NotificationChannel channel = new NotificationChannel().withSNSTopicArn(snsTopicArn).withRoleArn(roleArn);

		// ("TABLES", "FORMS")
		StartDocumentAnalysisRequest req = new StartDocumentAnalysisRequest().withFeatureTypes("TABLES", "FORMS")
				.withDocumentLocation(
						new DocumentLocation().withS3Object(new S3Object().withBucket(bucket).withName(document)))
				.withJobTag("AnalyzingText").withNotificationChannel(channel);

		StartDocumentAnalysisResult startDocumentAnalysisResult = textract.startDocumentAnalysis(req);
		startJobId = startDocumentAnalysisResult.getJobId();
	}

	// Gets the results of processing started by StartDocumentAnalysis
	private static void GetDocumentAnalysisResults() throws FileNotFoundException, Exception {

		int maxResults = 1000;
		String paginationToken = null;
		GetDocumentAnalysisResult response = null;
		Boolean finished = false;
		List<Block> allBlocks = new ArrayList<>();
		List<Block> keySetBlocks = new ArrayList<Block>();
		List<Block> valueSetBlocks = new ArrayList<Block>();
		List<Block> wordSetBlocks = new ArrayList<Block>();
		List<KeyValue> allKeyValues = new ArrayList<KeyValue>();
		List<Block> tableBlocks = new ArrayList<Block>();
		List<Block> cellBlocks = new ArrayList<Block>();
		List<Table> tablesList = new ArrayList<Table>();
		Map<String, Object> finalData = new LinkedHashMap<>();

		// loops until pagination token is null
		while (finished == false) {
			GetDocumentAnalysisRequest documentAnalysisRequest = new GetDocumentAnalysisRequest().withJobId(startJobId)
					.withMaxResults(maxResults).withNextToken(paginationToken);

			response = textract.getDocumentAnalysis(documentAnalysisRequest);

			DocumentMetadata documentMetaData = response.getDocumentMetadata();

//			System.out.println("Pages: " + documentMetaData.getPages().toString());

			// Show blocks, confidence and detection times
			List<Block> blocks = response.getBlocks();
			allBlocks.addAll(blocks);
//			for (Block block : blocks) {
//				DisplayBlockInfo(block);
//			}
			keySetBlocks.addAll(getBlocksByBlockType(blocks, "KEY_VALUE_SET", "KEY"));
			valueSetBlocks.addAll(getBlocksByBlockType(blocks, "KEY_VALUE_SET", "VALUE"));
			tableBlocks.addAll(getBlocksByBlockType(blocks, "TABLE", ""));
			wordSetBlocks.addAll(getBlocksByBlockType(blocks, "WORD", ""));
			cellBlocks.addAll(getBlocksByBlockType(blocks, "CELL", ""));

			paginationToken = response.getNextToken();
			if (paginationToken == null)
				finished = true;
		}

// ================ Form Key value pair output section start====================================

		allKeyValues = constructKeyValueSet(keySetBlocks, valueSetBlocks, wordSetBlocks);
//		for (KeyValue pairKeyValue : allKeyValues) {
//			System.out.println(pairKeyValue.toString());
//		}
// ================ Form Key value pair output section end====================================

// ================ Table construction section start==========================================

		for (Block tableBlock : tableBlocks) {
			Table table = new Table();
			table.setTableBlock(tableBlock);
			table.setCellBlocks(getCellBlockByTable(table, cellBlocks));
			table.setCellWords(getTableCellWords(table, wordSetBlocks));
			table.setCoulumnRowscount(getTableColumnRowCount(table));
			table.setColumnNamesList(getTableHeaders(table));
			table.setTableData(getTableData(table));
			tablesList.add(table);
//			System.out.println(table.getTableData());
		}

// ================ Table construction section end============================================


		// Using the formatted data and apply mapping
		JSONParser jsonParser = new JSONParser();
		FileReader reader = new FileReader("./jsonfile/mapping.json");
		Object obj = jsonParser.parse(reader);
		JSONObject jsonObj = (JSONObject) obj;
		JSONArray mappingArray = (JSONArray) jsonObj.get("mapping");
		JSONObject algorithm = getAlgorithm(mappingArray, allBlocks);
		if(!algorithm.isEmpty()) {
			finalData =	getFinalStatementData(algorithm, allKeyValues, tablesList);
			String jsonStr = JSONValue.toJSONString(finalData);
			System.out.println("Final Data=>"+jsonStr);
		} else {
			System.out.println("Algorithm / Mapping not found.");
		}
	}

	private static Map<String, Object> getFinalStatementData(JSONObject algorithm,  List<KeyValue> formData, List<Table> tablesList){
		Map<String, Object> finalData = new LinkedHashMap<>();
		Map<String, Object> formMapping = (Map<String, Object>) algorithm.get("form_mapping");
		Map<String, Object> tablesMapping = (Map<String, Object>) algorithm.get("tables");
		Map<String, List<Map<String, Object>>> tableType = new LinkedHashMap<>();

		// Adding data extracted using form feature
		Map<String, Object> formAttributes = new LinkedHashMap<>();
		formMapping.forEach((key, value) -> {
			for(KeyValue kVPair: formData) {
				if(value.equals(kVPair.getKey())) {
					formAttributes.put(key, kVPair.getValue());
				}
			}
		});

		// Adding data extracted using table feature
		tablesMapping.forEach((tableNameKey, tableHeadersObjValue) -> {
			Map<String, Object> mappedHeaders = (Map<String, Object>) tableHeadersObjValue;
			Map<String, String> tableHeadersMap = (Map<String, String>) tableHeadersObjValue;

			for(Table tableObj: tablesList) {
				List<Map<String, Object>> rowsData = new ArrayList<>();
				if(checkForHeadersMatch(tableHeadersMap, tableObj.getColumnNamesList())){
					for(Map<String, Object> singleRow: tableObj.getTableData()) {
						Map<String, Object> cellMap = new LinkedHashMap<>();
						mappedHeaders.forEach((attribute, mappedColName)->{
							cellMap.put(attribute, singleRow.get(mappedColName));
						});
						rowsData.add(cellMap);
					}
					Collections.reverse(rowsData);
					if (tableType.containsKey(tableNameKey)) {
						rowsData.addAll(tableType.get(tableNameKey));
						tableType.put(tableNameKey, rowsData);
					} else {
						tableType.put(tableNameKey, rowsData);
					}
				}
			}
		});
		finalData.put("form_data", formAttributes);
		finalData.put("tables", tableType);
		return finalData;
	}

	private static boolean checkForHeadersMatch(Map<String, String> tableHeadersMap, List<Map<Integer, String>> columnNameList) {
//		System.out.println("tableHeadersMap=>"+tableHeadersMap);
//		System.out.println("columnNameList=>"+columnNameList);
		boolean result = true;
		ArrayList columns = new ArrayList();
		for(int i=0; i < columnNameList.size(); i++) {
			columns.add(columnNameList.get(i).get(i));
		}
		for (Map.Entry<String, String> entry: tableHeadersMap.entrySet()) {
			if (!columns.contains(entry.getValue())) {
				result = false;
			}
		}
		return result;
	}

	private static JSONObject getAlgorithm(JSONArray mappingArray, List<Block> blockList) {
		JSONObject algorithm = new JSONObject();
		for(int i=0; i < mappingArray.size(); i++) {
			JSONObject mappingObj = (JSONObject) mappingArray.get(i);
			JSONArray detectionKeys = (JSONArray) mappingObj.get("detection_keys");
			for (int j = 0; j < detectionKeys.size(); j++) {
				JSONObject detectionKey = (JSONObject) detectionKeys.get(j);
				for(Block block: blockList){
				if(detectionKey.get("block_type").equals(block.getBlockType()) && detectionKey.get("Text").equals(block.getText())){
					algorithm = (JSONObject) mappingObj.get("algorithm");
					break;
				}
			}
			}
		}
		return algorithm;
	}



// =====================Methods of Table Construction sections start==============================

	private static List<Map<String, Object>> getTableData(Table table) {
		List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
		for (Relationship relationship : table.getTableBlock().getRelationships()) {
			if (relationship.getType().equals("CHILD")) {
				Map<String, Integer> colRowCount = table.getCoulumnRowscount();
				List<Map<Integer, String>> headers = table.getColumnNamesList();
				List<String> cellIds = relationship.getIds().subList(colRowCount.get("columns"),
						relationship.getIds().size());
				int columnNo = 0;
				Map<String, Object> rowMap = new LinkedHashMap<>();
				for (String id : cellIds) {
					try {
						Block cellBlock = table.getCellBlocks().stream().filter(cell -> id.equals(cell.getId()))
								.findFirst().orElse(null);
						Relationship cellRel = cellBlock.getRelationships().stream()
								.filter(rel -> rel.getType().equals("CHILD")).findFirst().orElse(null);
						String cellString = getWordString(table.getCellWords(), cellRel.getIds());
						rowMap.put(headers.get(columnNo).get(columnNo), cellString);
					} catch (NullPointerException e) {
						rowMap.put(headers.get(columnNo).get(columnNo), "NA");
					}

					if (columnNo == colRowCount.get("columns") - 1) {
						rows.add(rowMap);
						columnNo = 0;
						rowMap = new LinkedHashMap<>();
					} else {
						columnNo++;
					}
				}
			}
		}

		return rows;
	}

	private static List<Block> getTableCellWords(Table table, List<Block> words) {
		List<Block> wordBlockList = new ArrayList<Block>();
		for (Block cellBlock : table.getCellBlocks()) {
			try{
				for (Relationship relationship : cellBlock.getRelationships()) {
					if (relationship.getType().equals("CHILD")) {
						for (String id : relationship.getIds()) {
							wordBlockList.add(
									words.stream().filter(block -> (block.getId().equals(id))).findFirst().orElse(null));
						}
					}
				}
			} catch(NullPointerException e){

			}
		}
		return wordBlockList;
	}

	private static List<Block> getCellBlockByTable(Table table, List<Block> cellBlocks) {
		List<Block> cellBlockList = new ArrayList<Block>();
		for (Relationship relationship : table.getTableBlock().getRelationships()) {
			if (relationship.getType().equals("CHILD")) {
				for (String id : relationship.getIds()) {
					cellBlockList.add(
							cellBlocks.stream().filter(block -> (block.getId().equals(id))).findFirst().orElse(null));
				}
			}
		}
		return cellBlockList;
	}

	private static List<Map<Integer, String>> getTableHeaders(Table table) {
		List<Map<Integer, String>> headersList = new ArrayList<Map<Integer, String>>();
		for (Relationship relationship : table.getTableBlock().getRelationships()) {
			if (relationship.getType().equals("CHILD")) {
				List<String> idsList = relationship.getIds();
				Map<String, Integer> colRowcount = table.getCoulumnRowscount();
				for (int i = 0; i < colRowcount.get("columns"); i++) {
					for (Block cellBlock : table.getCellBlocks()) {
						if (cellBlock.getId().equals(idsList.get(i))) {
							Map<Integer, String> hederNameMap = new HashMap<>();
							try {
								for (Relationship rel : cellBlock.getRelationships()) {
									if (rel.getType().equals("CHILD")) {
										String headerName = getWordString(table.getCellWords(), rel.getIds());
										hederNameMap.put(i, headerName);
										headersList.add(hederNameMap);
									}
								}
							} catch (NullPointerException e) {
								hederNameMap.put(i, "No Header");
								headersList.add(hederNameMap);
							}
						}
					}
				}

			}
		}
		return headersList;
	}

	// Method to get row and columns count available in table
	private static Map<String, Integer> getTableColumnRowCount(Table table) {
		Map<String, Integer> map = new HashMap<String, Integer>();
		for (Relationship relationship : table.getTableBlock().getRelationships()) {
			if (relationship.getType().equals("CHILD")) {
				String lastIdString = relationship.getIds().get(relationship.getIds().size() - 1);
				for (Block ceBlock : table.getCellBlocks()) {
					if (ceBlock.getId().equals(lastIdString)) {
						map.put("columns", ceBlock.getColumnIndex());
						map.put("rows", ceBlock.getRowIndex());
					}
				}

			}
		}
		return map;
	}

// =====================Methods of Table Construction sections end==============================

// ===============================================Methods for Form Key Value Constructions Section=============================================

	private static List<KeyValue> constructKeyValueSet(List<Block> keys, List<Block> values, List<Block> words) {
		List<KeyValue> keyValuePairs = new ArrayList<KeyValue>();
		String keyString = null;
		String valueString = null;
		for (Block key : keys) {
			for (Relationship element : key.getRelationships()) {
				if (element.getType().equals("VALUE")) {
					ArrayList<String> valueIds = (ArrayList<String>) element.getIds();
					for (Block value : values) {
						if (value.getId().equals(valueIds.get(0))) {
							for (Relationship item : value.getRelationships()) {
								if (item.getType().equals("CHILD")) {
									valueString = getWordString(words, item.getIds());
								}
							}
						}
					}
				}
				if (element.getType().equals("CHILD")) {
					keyString = getWordString(words, element.getIds()).replaceAll(":", "");
				}
			}
			keyValuePairs.add(new KeyValue(keyString, valueString));
		}
		return keyValuePairs;
	}

	private static String getWordString(List<Block> words, List<String> ids) {
		String lineString = "";
		for (String id : ids) {
			for (Block wordBlock : words) {
				if (wordBlock.getId().equals(id)) {
					lineString += " " + wordBlock.getText();
				}
			}
		}
		return lineString.trim();
	}

	private static List<Block> getBlocksByBlockType(List<Block> blocks, String blockType, String entityType) {
		Predicate<Block> byBlockType = block -> (block.getBlockType().equals(blockType)
				&& block.getEntityTypes().contains(entityType));
		if (entityType.isEmpty())
			byBlockType = block -> block.getBlockType().equals(blockType);
		List<Block> keyValueSetBlocks = blocks.stream().filter(byBlockType).collect(Collectors.toList());
		return keyValueSetBlocks;
	}

// =========================================================Methods for Form Key Value Constructions Section=============================================

	// Displays Block information for text detection and text analysis
	private static void DisplayBlockInfo(Block block) {
		System.out.println(block);
//		if (!block.getBlockType().equals("LINE")) {
//			System.out.println(block);
//		}
//		System.out.println("Block Id : " + block.getId());
//		if (block.getText() != null)
//			System.out.println("\tDetected text: " + block.getText());
//		System.out.println("\tType: " + block.getBlockType());
//
//		if (block.getBlockType().equals("PAGE") != true) {
//			System.out.println("\tConfidence: " + block.getConfidence().toString());
//		}
//		if (block.getBlockType().equals("CELL")) {
//			System.out.println("\tCell information:");
//			System.out.println("\t\tColumn: " + block.getColumnIndex());
//			System.out.println("\t\tRow: " + block.getRowIndex());
//			System.out.println("\t\tColumn span: " + block.getColumnSpan());
//			System.out.println("\t\tRow span: " + block.getRowSpan());
//
//		}
//
//		System.out.println("\tRelationships");
//		List<Relationship> relationships = block.getRelationships();
//		if (relationships != null) {
//			for (Relationship relationship : relationships) {
//				System.out.println("\t\tType: " + relationship.getType());
//				System.out.println("\t\tIDs: " + relationship.getIds().toString());
//			}
//		} else {
//			System.out.println("\t\tNo related Blocks");
//		}
//
//		System.out.println("\tGeometry");
//		System.out.println("\t\tBounding Box: " + block.getGeometry().getBoundingBox().toString());
//		System.out.println("\t\tPolygon: " + block.getGeometry().getPolygon().toString());
//
//		List<String> entityTypes = block.getEntityTypes();
//
//		System.out.println("\tEntity Types");
//		if (entityTypes != null) {
//			for (String entityType : entityTypes) {
//				System.out.println("\t\tEntity Type: " + entityType);
//			}
//		} else {
//			System.out.println("\t\tNo entity type");
//		}
//
//		if (block.getBlockType().equals("SELECTION_ELEMENT")) {
//			System.out.print("    Selection element detected: ");
//			if (block.getSelectionStatus().equals("SELECTED")) {
//				System.out.println("Selected");
//			} else {
//				System.out.println(" Not selected");
//			}
//		}
//		if (block.getPage() != null)
//			System.out.println("\tPage: " + block.getPage());
//		System.out.println();
	}
}

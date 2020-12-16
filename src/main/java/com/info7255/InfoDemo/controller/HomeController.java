package com.info7255.InfoDemo.controller;


import com.info7255.InfoDemo.beans.EtagManager;
import com.info7255.InfoDemo.beans.JSONValidator;
import com.info7255.InfoDemo.beans.JedisBean;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.apache.http.HttpHost;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.everit.json.schema.Schema;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

@RestController
public class HomeController {

	@Autowired
	private JSONValidator validator;
	@Autowired
	private JedisBean jedisBean;

	@Autowired
	private EtagManager etagManager;
	@Autowired
	private RedisSender redisSender;

	private RestHighLevelClient client = new RestHighLevelClient(RestClient.builder(new HttpHost("localhost",9200)));
	private String key = "ssdkF$HUy2A#D%kd";
	private String algorithm = "AES";

	private RSAKey rsaPublicJWK;

	Map<String, Object> m = new HashMap<String, Object>();

	@RequestMapping("/")
	public String home() {
		return "Welcome!";
		
		
	}

	@GetMapping(value = "/plan/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> read(@PathVariable(name = "id", required = true) String id,
                                                    @RequestHeader HttpHeaders requestHeaders) throws ParseException, JOSEException {
		m.clear();
		if (!ifAuthorized(requestHeaders)) {
			m.put("message", "Authorization failed");
			return new ResponseEntity<Map<String, Object>>(m, HttpStatus.UNAUTHORIZED);
		}

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);

		JSONObject jsonString = jedisBean.read(id);
		if (jsonString != null) {
			String etag = etagManager.getETag(jsonString);
			if(requestHeaders.get("If-None-Match") == null){
				headers.setETag(etag);
				return new ResponseEntity<Map<String, Object>>(jsonString.toMap(), headers, HttpStatus.OK);
			}

			if (!etagManager.verifyETag(jsonString, requestHeaders.get("If-None-Match"))) {
				headers.setETag(etag);
				return new ResponseEntity<Map<String, Object>>(jsonString.toMap(), headers, HttpStatus.OK);
			} else {
				headers.setETag(etag);
				return new ResponseEntity<Map<String, Object>>(m, headers, HttpStatus.NOT_MODIFIED);
			}
		} else {
			m.put("message", "Read unsuccessful. Invalid Id.");
			return new ResponseEntity<>(m, headers, HttpStatus.NOT_FOUND);
		}

	}

	@PostMapping(value = "/plan", produces = MediaType.APPLICATION_JSON_VALUE, consumes = "application/json")
	public ResponseEntity<Map<String, Object>> insert(@RequestBody(required = true) String body,
                                                      @RequestHeader HttpHeaders requestHeaders) throws ParseException, JOSEException, IOException {
		m.clear();
		if (!ifAuthorized(requestHeaders)) {
			m.put("message", "Authorization failed");
			return new ResponseEntity<Map<String, Object>>(m, HttpStatus.UNAUTHORIZED);
		}

		Schema schema = validator.getSchema();
		if (schema == null) {
			m.put("message", "No Schema found");
			return new ResponseEntity<Map<String, Object>>(m, HttpStatus.NOT_FOUND);
		}
		
		JSONObject jsonObject = validator.getJsonObjectFromString(body);
		String id = (String) jsonObject.get("objectId");
		JSONObject jsonString = jedisBean.read(id);

		if (jsonString != null) {
			m.put("message", "This objectId has been used");
			return new ResponseEntity<Map<String, Object>>(m, HttpStatus.BAD_REQUEST);
		}
		if (validator.validate(jsonObject)) {
			String uuid = jedisBean.insert(jsonObject);
			String etag=etagManager.getETag(jsonObject);

			//send message
			redisSender.sendDataToRedisQueue(jsonObject.toString());

			HttpHeaders responseHeader=new HttpHeaders();
			responseHeader.setETag(etag);
			m.put("message", "Added successfully");
			m.put("id", uuid);
			return new ResponseEntity<Map<String, Object>>(m,responseHeader, HttpStatus.CREATED);
		} else {
			m.put("message", "Validation failed");
			return new ResponseEntity<Map<String, Object>>(m, HttpStatus.BAD_REQUEST);
		}

	}

	@DeleteMapping(value = "/plan/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> delete(@PathVariable(name = "id", required = true) String id,
                                                      @RequestHeader HttpHeaders requestHeaders) throws ParseException, JOSEException {
		m.clear();
		JSONObject jsonString = jedisBean.read(id);
		if(jsonString == null) {
			m.put("message", "Invalid Plan Id");
			return new ResponseEntity<Map<String, Object>>(m, HttpStatus.BAD_REQUEST);
		}
		String body = jsonString.toString();
		if (!ifAuthorized(requestHeaders)) {
			m.put("message", "Authorization failed");
			return new ResponseEntity<Map<String, Object>>(m, HttpStatus.UNAUTHORIZED);
		}
		if (jedisBean.delete(body)) {
			m.put("message", "Deleted successfully");
			return new ResponseEntity<Map<String, Object>>(m, HttpStatus.ACCEPTED);
		} else {
			m.put("message", "Delete failed");
			return new ResponseEntity<Map<String, Object>>(m, HttpStatus.BAD_REQUEST);
		}
	}

	@RequestMapping(method= RequestMethod.PATCH,value = "/plan/{planID}", produces = MediaType.APPLICATION_JSON_VALUE, consumes = "application/json")
	public ResponseEntity<Map<String, Object>> update(@PathVariable(name = "planID", required = true) String planID, @RequestBody(required = true) String body,
                                                      @RequestHeader HttpHeaders requestHeaders) throws ParseException, JOSEException, IOException {
		m.clear();
		if (!ifAuthorized(requestHeaders)) {
			m.put("message", "Authorization failed");
			return new ResponseEntity<Map<String, Object>>(m, HttpStatus.UNAUTHORIZED);
		}
		Schema schema = validator.getSchema();
		if (schema == null) {	
			m.put("message", "No schema found!");
			return new ResponseEntity<Map<String, Object>>(m, HttpStatus.NOT_FOUND);
		}
		
//		System.out.println("landmark 1");
		JSONObject jsonObject = validator.getJsonObjectFromString(body);

		HttpHeaders responseHeaders = new HttpHeaders();
		responseHeaders.setContentType(MediaType.APPLICATION_JSON);
		
		JSONObject planJSON=jedisBean.read(planID);



		if(planJSON!=null)
		{
//			System.out.println("landmark 2");
			//System.out.println(planJSON);
			String etag = etagManager.getETag(planJSON);
			//System.out.println("etag");
			//System.out.println(etag);
//			System.out.println(requestHeaders.get("If-Match").get(0));
			if(requestHeaders.get("If-Match")==null){
				m.put("message","If-Match ETag required");
				return new ResponseEntity<Map<String, Object>>(m, responseHeaders, HttpStatus.PRECONDITION_REQUIRED);
			}

			if (etagManager.verifyETag(planJSON, requestHeaders.get("If-Match"))) {
				//System.out.println("etagtrue");
				String newETag=jedisBean.patch(jsonObject,planID);

				JSONObject jsonObject1=jedisBean.read(planID);


				redisSender.sendDataToRedisQueue(jsonObject1.toString());

//				IndexRequest request1;
//				IndexRequest request2;
//				for(Object key : jsonObject.keySet()){
//					String s = key.toString();
//
//					if(s.equals("planCostShares")){
//						JSONObject j1 = (JSONObject)jsonObject1.get(s);
//						HashMap<String, Object> m1 = new HashMap<>();
//						HashMap<String, Object> mm1 = new HashMap<>();
//						request1 = new IndexRequest("plan").id(j1.get("objectId").toString()).routing(jsonObject1.get("objectId").toString());
//						for(Object key1 : j1.keySet()){
//							String s1 = key1.toString();
//							m1.put(s1,j1.get(s1));
//							mm1.put("parent",jsonObject1.get("objectId"));
//							mm1.put("name",j1.get("objectType"));
//							m1.put("plan_service",mm1);
//
//						}
//						request1.source(m1);
//						client.index(request1,RequestOptions.DEFAULT);
//					}
//
//
//					if(s.equals("linkedPlanServices")){
//						JSONArray jsonArray = (JSONArray) jsonObject1.get(s);
//						for(int i = 0 ; i <jsonArray.length(); i++){
//							JSONObject o = (JSONObject) jsonArray.get(i);
//							for(Object key4 : o.keySet()){
//								System.out.println(key4.toString());
//							}
//							for(Object key3 : o.keySet()){
//								String s3 = key3.toString();
//
//
//
//								if(s3.equals("linkedService") || s3.equals("planserviceCostShares")){
//									if (o.get(s3) instanceof JSONObject) {
//										JSONObject j2 = (JSONObject)o.get(s3);
//										HashMap<String, Object> m1 = new HashMap<>();
//										HashMap<String, Object> mm1 = new HashMap<>();
//										request1 = new IndexRequest("plan").id(j2.get("objectId").toString()).routing(o.get("objectId").toString());
//										for(Object key1 : j2.keySet()){
//											String s1 = key1.toString();
//											m1.put(s1,j2.get(s1));
//											mm1.put("parent",o.get("objectId"));
//											mm1.put("name",j2.get("objectType"));
//											m1.put("plan_service",mm1);
//
//										}
//										request1.source(m1);
//										client.index(request1,RequestOptions.DEFAULT);
//
//									}
//
//									if(o.get(s3) instanceof JSONArray){
//										JSONArray jsonArray1 = (JSONArray) o.get(s3);
//										for(int j = 0; j < jsonArray1.length(); j++){
//											JSONObject o1 = (JSONObject) jsonArray1.get(j);
//											HashMap<String, Object> m1 = new HashMap<>();
//											HashMap<String, Object> mm1 = new HashMap<>();
//											request1 = new IndexRequest("plan").id(o1.get("objectId").toString()).routing(o.get("objectId").toString());
//											for(Object key1 : o1.keySet()){
//												String s1 = key1.toString();
//												m1.put(s1,o1.get(s1));
//												mm1.put("parent",o.get("objectId"));
//												mm1.put("name",o1.get("objectType"));
//												m1.put("plan_service",mm1);
//
//											}
//											request1.source(m1);
//											client.index(request1,RequestOptions.DEFAULT);
//
//										}
//									}
//
//
//
//								}
//							}
//
//							HashMap<String, Object> m2 = new HashMap<>();
//							HashMap<String, Object> mm2 = new HashMap<>();
//							request2 = new IndexRequest("plan").id(o.get("objectId").toString()).routing(jsonObject1.get("objectId").toString());
//							for(Object key2 : o.keySet()){
//								if(!key2.equals("linkedService") && !key2.equals("planserviceCostShares")){
//									String s2 = key2.toString();
//									m2.put(s2,o.get(s2));
//									mm2.put("name", o.get("objectType"));
//									mm2.put("parent", jsonObject1.get("objectId"));
//									m2.put("plan_service",mm2);
//
//								}
//							}
//							request2.source(m2);
//							client.index(request2,RequestOptions.DEFAULT);
//						}
//
//
//					}
//
//
//					HashMap<String, Object> m2 = new HashMap<>();
//					HashMap<String, Object> mm2 = new HashMap<>();
//					request2 = new IndexRequest("plan").id(jsonObject1.get("objectId").toString());
//					for(Object key2 : jsonObject1.keySet()){
//						if(!key2.equals("planCostShares") && !key2.equals("linkedPlanServices")){
//
//							String s2 = key2.toString();
//							m2.put(s2,jsonObject1.get(s2));
//							mm2.put("name", jsonObject1.get("objectType"));
//							m2.put("plan_service",mm2);
//
//						}
//					}
//					request2.source(m2);
//					client.index(request2,RequestOptions.DEFAULT);





				//}
//				System.out.println("landmark 3");
				if (newETag==null) {
//					System.out.println("landmark 4");
					m.put("message", "Update failed");
					return new ResponseEntity<Map<String, Object>>(m, HttpStatus.BAD_REQUEST);
				}
				//String newETag=etagManager.getETag(jedisBean.read(planID));
//				System.out.println("landmark 5");
				responseHeaders.setETag(newETag);
				m.put("message", "Updated successfully");
				return new ResponseEntity<Map<String, Object>>(m, responseHeaders, HttpStatus.ACCEPTED);
			}

			else{
				//System.out.println(requestHeaders.get("If-Match").get(0));
				m.put("message","Etag is not correct");
				return new ResponseEntity<Map<String, Object>>(m, responseHeaders, HttpStatus.ACCEPTED);
			}

//
//			if (etagManager.verifyETag(planJSON, requestHeaders.get("If-Match"))) {
//				System.out.println("etagtrue");
//				String newETag=jedisBean.patch(jsonObject,planID);
////				System.out.println("landmark 3");
//				if (newETag==null) {
////					System.out.println("landmark 4");
//					m.put("message", "Update failed");
//					return new ResponseEntity<Map<String, Object>>(m, HttpStatus.BAD_REQUEST);
//				}
//				//String newETag=etagManager.getETag(jedisBean.read(planID));
////				System.out.println("landmark 5");
//				responseHeaders.setETag(newETag);
//				m.put("message", "Updated successfully");
//				return new ResponseEntity<Map<String, Object>>(m, responseHeaders, HttpStatus.ACCEPTED);
//			} else {
////				System.out.println("landmark 6");
//				if(requestHeaders.getIfMatch().isEmpty()) {
////					System.out.println("landmark 7");
//					m.put("message","If-Match ETag required");
//					return new ResponseEntity<Map<String, Object>>(m, responseHeaders, HttpStatus.PRECONDITION_REQUIRED);
//				}
//				else {
////					System.out.println("landmark 8");
//					responseHeaders.setETag(etag);
//					return new ResponseEntity<Map<String, Object>>(m, responseHeaders, HttpStatus.PRECONDITION_FAILED);
//
//				}
//			}
		}
		else {

		m.put("message", "Invalid Plan Id");
		return new ResponseEntity<Map<String, Object>>(m, HttpStatus.BAD_REQUEST);
		}
	}
	@RequestMapping(method= RequestMethod.PUT,value = "/plan/{planID}", produces = MediaType.APPLICATION_JSON_VALUE, consumes = "application/json")
	public ResponseEntity<Map<String, Object>> update1(@PathVariable(name = "planID", required = true) String planID, @RequestBody(required = true) String body,
                                                       @RequestHeader HttpHeaders requestHeaders) throws ParseException, JOSEException {
		m.clear();
		if (!ifAuthorized(requestHeaders)) {
			m.put("message", "Authorization failed");
			return new ResponseEntity<Map<String, Object>>(m, HttpStatus.UNAUTHORIZED);
		}

		Schema schema = validator.getSchema();
		if (schema == null) {	
			m.put("message", "No schema found!");
			return new ResponseEntity<Map<String, Object>>(m, HttpStatus.NOT_FOUND);
		}
		


		JSONObject jsonObject = validator.getJsonObjectFromString(body);
		if(!validator.validate(jsonObject)){
			m.put("message", "Validation failed");
			return new ResponseEntity<Map<String, Object>>(m, HttpStatus.BAD_REQUEST);
		}

		HttpHeaders responseHeaders = new HttpHeaders();
		responseHeaders.setContentType(MediaType.APPLICATION_JSON);
		
		JSONObject planJSON=jedisBean.read(planID);
		if(planJSON!=null)
		{
			String etag = etagManager.getETag(planJSON);
			if(requestHeaders.get("If-Match")==null){
				m.put("message","If-Match ETag required");
				return new ResponseEntity<Map<String, Object>>(m, responseHeaders, HttpStatus.PRECONDITION_REQUIRED);
			}


			if (etagManager.verifyETag(planJSON, requestHeaders.get("If-Match"))) {
			
				if (!jedisBean.replace(jsonObject)) {
					m.put("message", "Update failed");
					return new ResponseEntity<Map<String, Object>>(m, HttpStatus.BAD_REQUEST);
				}
				jedisBean.replace(jsonObject);
				String newETag=etagManager.getETag(jedisBean.read(planID));
				responseHeaders.setETag(newETag);
				m.put("message", "Update successfully!");
				return new ResponseEntity<Map<String, Object>>(m, responseHeaders, HttpStatus.ACCEPTED);
///				return new ResponseEntity<Map<String, Object>>(m, responseHeaders, HttpStatus.NO_CONTENT);
			}
			else{
				m.put("message","Etag is not correct");
				return new ResponseEntity<Map<String, Object>>(m, responseHeaders, HttpStatus.ACCEPTED);
			}
//			else {
//				if(requestHeaders.getIfMatch().isEmpty()) {
//					m.put("message","If-Match ETag required");
//					return new ResponseEntity<Map<String, Object>>(m, responseHeaders, HttpStatus.PRECONDITION_REQUIRED);
//				}
//				else {
//					responseHeaders.setETag(etag);
//					return new ResponseEntity<Map<String, Object>>(m, responseHeaders, HttpStatus.PRECONDITION_FAILED);
//
//				}
//			}
		}
		else {

		m.put("message", "Invalid Plan Id");
		return new ResponseEntity<Map<String, Object>>(m, HttpStatus.BAD_REQUEST);
		}
	}
	@GetMapping(value = "/token", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> createToken() throws JOSEException {

		m.clear();

		RSAKey rsaJWK = new RSAKeyGenerator(2048).keyID("info7255").generate();
		rsaPublicJWK = rsaJWK.toPublicJWK();
		// verifier = new RSASSAVerifier(rsaPublicJWK);

		// Create RSA-signer with the private key
		JWSSigner signer = null;
		try {
			signer = new RSASSASigner(rsaJWK);
		} catch (JOSEException e) {
			e.printStackTrace();
		}

		// Prepare JWT with claims set
		int expireTime = 30000; // seconds

		JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
				.expirationTime(new Date(new Date().getTime() + expireTime * 1000)) // milliseconds
				.build();

		SignedJWT signedJWT = new SignedJWT(
				new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaJWK.getKeyID()).build(),
				claimsSet);

		// Compute the RSA signature
		try {
			signedJWT.sign(signer);
		} catch (JOSEException e) {
			e.printStackTrace();
		}

		// To serialize to compact form, produces something like
		// eyJhbGciOiJSUzI1NiJ9.SW4gUlNBIHdlIHRydXN0IQ.IRMQENi4nJyp4er2L
		// mZq3ivwoAjqa1uUkSBKFIX7ATndFF5ivnt-m8uApHO4kfIFOrW7w2Ezmlg3Qd
		// maXlS9DhN0nUk_hGI3amEjkKd0BWYCB8vfUbUv0XGjQip78AI4z1PrFRNidm7
		// -jPDm5Iq0SZnjKjCNS5Q15fokXZc8u0A
		String token = signedJWT.serialize();


//		String hyy = JwtUtils.createAccessJwtToken("hyy");
//		System.out.println(hyy);

		JSONObject jsonToken = new JSONObject();

		TimeZone tz = TimeZone.getTimeZone("UTC");
		// yyyy-MM-dd'T'HH:mm'Z'
		DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'");
		df.setTimeZone(tz);

		Calendar calendar = Calendar.getInstance();
		calendar.setTime(new Date());
		calendar.add(Calendar.MINUTE, 60);
		Date date = calendar.getTime();

		jsonToken.put("expiry", df.format(date));
//		token = jsonToken.toString() + "YiyingHu";
		//System.out.println(token);

		SecretKey spec = loadKey();

		try {
			Cipher c = Cipher.getInstance(algorithm);
			c.init(Cipher.ENCRYPT_MODE, spec);
			byte[] encrBytes = c.doFinal(token.getBytes());
			String encoded = Base64.getEncoder().encodeToString(encrBytes);
			m.put("token", token);
//			m.put("expiry", df.format(date));
			return new ResponseEntity<Map<String, Object>>(m, HttpStatus.ACCEPTED);

		} catch (Exception e) {
			e.printStackTrace();
			m.put("message", "Token creation failed. Please try again.");
			return new ResponseEntity<Map<String, Object>>(m, HttpStatus.UNAUTHORIZED);
		}




	}

	private boolean ifAuthorized(HttpHeaders requestHeaders) throws ParseException, JOSEException {
		String token = requestHeaders.getFirst("Authorization").substring(7);

		// On the consumer side, parse the JWS and verify its RSA signature
		SignedJWT signedJWT = SignedJWT.parse(token);
		if(rsaPublicJWK == null || signedJWT == null){
			return false;
		}
		JWSVerifier verifier = new RSASSAVerifier(rsaPublicJWK);
		// Retrieve / verify the JWT claims according to the app requirements
		if (!signedJWT.verify(verifier)) {
			return false;
		}
		JWTClaimsSet claimset = signedJWT.getJWTClaimsSet();
		Date exp = 	claimset.getExpirationTime();

		// System.out.println(exp);
		// System.out.println(new Date());

		return new Date().before(exp);
	}

	
	private SecretKey loadKey() {
		return new SecretKeySpec(key.getBytes(), algorithm);
	}

	public void setIndex(Message message) throws IOException {
		IndexRequest request1;
		IndexRequest request2;
		JSONObject jsonObject = new JSONObject(message.toString());
		for(Object key : jsonObject.keySet()){
			String s = key.toString();

			if(s.equals("planCostShares")){
				JSONObject j1 = (JSONObject)jsonObject.get(s);
				HashMap<String, Object> m1 = new HashMap<>();
				HashMap<String, Object> mm1 = new HashMap<>();
				request1 = new IndexRequest("plan").id(j1.get("objectId").toString()).routing(jsonObject.get("objectId").toString());
				for(Object key1 : j1.keySet()){
					String s1 = key1.toString();
					m1.put(s1,j1.get(s1));
					mm1.put("parent",jsonObject.get("objectId"));
					mm1.put("name",j1.get("objectType"));
					m1.put("plan_service",mm1);

				}
				request1.source(m1);
				client.index(request1, RequestOptions.DEFAULT);
			}


			if(s.equals("linkedPlanServices")){
				JSONArray jsonArray = (JSONArray) jsonObject.get(s);
				for(int i = 0 ; i <jsonArray.length(); i++){
					JSONObject o = (JSONObject) jsonArray.get(i);
					for(Object key4 : o.keySet()){
					}
					for(Object key3 : o.keySet()){
						String s3 = key3.toString();


						if(s3.equals("linkedService") || s3.equals("planserviceCostShares")){
//							JSONObject j2 = (JSONObject)o.get(s3);
////							HashMap<String, Object> m1 = new HashMap<>();
////							HashMap<String, Object> mm1 = new HashMap<>();
////							request1 = new IndexRequest("plan").id(j2.get("objectId").toString()).routing(o.get("objectId").toString());
////							for(Object key1 : j2.keySet()){
////								String s1 = key1.toString();
////								m1.put(s1,j2.get(s1));
////								mm1.put("parent",o.get("objectId"));
////								mm1.put("name",j2.get("objectType"));
////								m1.put("plan_service",mm1);
////
////							}
////							request1.source(m1);
////							client.index(request1,RequestOptions.DEFAULT);
							if (o.get(s3) instanceof JSONObject) {
								JSONObject j2 = (JSONObject)o.get(s3);
								HashMap<String, Object> m1 = new HashMap<>();
								HashMap<String, Object> mm1 = new HashMap<>();
								request1 = new IndexRequest("plan").id(j2.get("objectId").toString()).routing(o.get("objectId").toString());
								for(Object key1 : j2.keySet()){
									String s1 = key1.toString();
									m1.put(s1,j2.get(s1));
									mm1.put("parent",o.get("objectId"));
									mm1.put("name",j2.get("objectType"));
									m1.put("plan_service",mm1);

								}
								request1.source(m1);
								client.index(request1,RequestOptions.DEFAULT);

							}

							if(o.get(s3) instanceof JSONArray){
								JSONArray jsonArray1 = (JSONArray) o.get(s3);
								for(int j = 0; j < jsonArray1.length(); j++){
									JSONObject o1 = (JSONObject) jsonArray1.get(j);
									HashMap<String, Object> m1 = new HashMap<>();
									HashMap<String, Object> mm1 = new HashMap<>();
									request1 = new IndexRequest("plan").id(o1.get("objectId").toString()).routing(o.get("objectId").toString());
									for(Object key1 : o1.keySet()){
										String s1 = key1.toString();
										m1.put(s1,o1.get(s1));
										mm1.put("parent",o.get("objectId"));
										mm1.put("name",o1.get("objectType"));
										m1.put("plan_service",mm1);

									}
									request1.source(m1);
									client.index(request1,RequestOptions.DEFAULT);

								}
							}
						}
					}

					HashMap<String, Object> m2 = new HashMap<>();
					HashMap<String, Object> mm2 = new HashMap<>();
					request2 = new IndexRequest("plan").id(o.get("objectId").toString()).routing(jsonObject.get("objectId").toString());
					for(Object key2 : o.keySet()){
						if(!key2.equals("linkedService") && !key2.equals("planserviceCostShares")){
							String s2 = key2.toString();
							m2.put(s2,o.get(s2));
							mm2.put("name", o.get("objectType"));
							mm2.put("parent", jsonObject.get("objectId"));
							m2.put("plan_service",mm2);

						}
					}
					request2.source(m2);
					client.index(request2,RequestOptions.DEFAULT);
				}


			}


			HashMap<String, Object> m2 = new HashMap<>();
			HashMap<String, Object> mm2 = new HashMap<>();
			request2 = new IndexRequest("plan").id(jsonObject.get("objectId").toString());
			for(Object key2 : jsonObject.keySet()){
				if(!key2.equals("planCostShares") && !key2.equals("linkedPlanServices")){

					String s2 = key2.toString();
					m2.put(s2,jsonObject.get(s2));
					mm2.put("name", jsonObject.get("objectType"));
					m2.put("plan_service",mm2);

				}
			}
			request2.source(m2);
			client.index(request2,RequestOptions.DEFAULT);

		}
	}
}

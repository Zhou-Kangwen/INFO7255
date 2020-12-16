package com.info7255.InfoDemo.beans;

import com.info7255.InfoDemo.util.Utils;

import io.lettuce.core.RedisException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisException;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class JedisBean {

	private static JedisPool pool = null;
	private static final String SEP = "____";
	private static final String redisHost = "localhost";
	private static final Integer redisPort = 6379;
	@Autowired
	private EtagManager etagManager;

	@Autowired
	public JedisBean() {
		pool = new JedisPool(redisHost, redisPort);
	}

	public boolean insertSchema(String schema) {
		try {
			Jedis jedis = pool.getResource();
			if (jedis.set("plan_schema", schema).equals("OK"))
				return true;
			else
				return false;
		} catch (JedisException e) {
			e.printStackTrace();
			return false;
		}
	}

	public String getSchema() {
		try {
			Jedis jedis = pool.getResource();
			return jedis.get("plan_schema");
		} catch (JedisException e) {
			e.printStackTrace();
			return null;
		}
	}

	public String insert(JSONObject jsonObject) {
		String idOne = jsonObject.getString("objectType") + SEP + jsonObject.getString("objectId");
		if (insertUtil(jsonObject, idOne)) {
//			System.out.println("LANDMARK12");
			Jedis jedis = pool.getResource();
			jedis.rpush(Utils.IndexAllQueue,jsonObject.toString());
		    jedis.close();

			return jsonObject.getString("objectId");
			}
		else
			return null;
	}

	private boolean insertUtil(JSONObject jsonObject, String uuid) {

		try {
			Jedis jedis = pool.getResource();
			Map<String, String> simpleMap = new HashMap<String, String>();

			for (Object key : jsonObject.keySet()) {
				String attributeKey = String.valueOf(key);
				Object attributeVal = jsonObject.get(String.valueOf(key));
				String edge = attributeKey;
				if (attributeVal instanceof JSONObject) {

					JSONObject embdObject = (JSONObject) attributeVal;
					String setKey = uuid + SEP + edge;
					String embd_uuid = embdObject.get("objectType") + SEP + embdObject.getString("objectId");
					jedis.sadd(setKey, embd_uuid);

					jedis.set(embd_uuid + SEP + "inv_" + edge, uuid);
					insertUtil(embdObject, embd_uuid);

				} else if (attributeVal instanceof JSONArray) {

					JSONArray jsonArray = (JSONArray) attributeVal;
					Iterator<Object> jsonIterator = jsonArray.iterator();
					String setKey = uuid + SEP + edge;

					while (jsonIterator.hasNext()) {
						JSONObject embdObject = (JSONObject) jsonIterator.next();
						if(!embdObject.isEmpty()) {
//							System.out.println("embdObject: ");
//							System.out.println(embdObject);
							String embd_uuid = embdObject.get("objectType") + SEP + embdObject.getString("objectId");
							jedis.sadd(setKey, embd_uuid);
							jedis.set(embd_uuid + SEP + "inv_" + edge, uuid);
							insertUtil(embdObject, embd_uuid);
						}
					}

				} else {
					simpleMap.put(attributeKey, String.valueOf(attributeVal));
				}
			}
			jedis.hmset(uuid, simpleMap);
			jedis.close();
		} catch (JedisException e) {
			e.printStackTrace();
			return false;
		}

		return true;
	}

	public JSONObject read(String id) {

		JSONObject jsonObject = readUtil("plan" + SEP + id);
		if (!jsonObject.isEmpty())
			return jsonObject;
		else
			return null;
	}

//	public JSONObject readObject(String uuid) {
//
//		String embd_uuid = embdObject.get("objectType") + SEP + id;
//		
//		JSONObject jsonObject = readUtil("plan" + SEP + id);
//		if (!jsonObject.isEmpty())
//			return jsonObject;
//		else
//			return null;
//	}
	
	private JSONObject readUtil(String uuid) {
		try {
			Jedis jedis = pool.getResource();
			JSONObject o = new JSONObject();

			Set<String> keys = jedis.keys(uuid + SEP + "*");
			keys.removeIf(key -> key.contains("inv"));

			// object members
			for (String key : keys) {
				Set<String> jsonKeySet = jedis.smembers(key);

				if (jsonKeySet.size() > 1) {

					JSONArray ja = new JSONArray();
					Iterator<String> jsonKeySetIterator = jsonKeySet.iterator();
					while (jsonKeySetIterator.hasNext()) {
						String nextKey = jsonKeySetIterator.next();
						ja.put(readUtil(nextKey));
					}
					o.put(key.substring(key.lastIndexOf(SEP) + 4), ja);
				} else {

					Iterator<String> jsonKeySetIterator = jsonKeySet.iterator();
					JSONObject embdObject = null;
					while (jsonKeySetIterator.hasNext()) {
						String nextKey = jsonKeySetIterator.next();
						embdObject = readUtil(nextKey);
					}
					o.put(key.substring(key.lastIndexOf(SEP) + 4), embdObject);

				}

			}

			// simple members
			Map<String, String> simpleMap = jedis.hgetAll(uuid);
			for (String simpleKey : simpleMap.keySet()) {
				o.put(simpleKey, simpleMap.get(simpleKey));
			}

			jedis.close();
			return o;
		} catch (RedisException e) {
			e.printStackTrace();
			return null;
		}
	}

	public String patch(JSONObject jsonObject, String planId) {
//		System.out.println("json object");
//		System.out.println(jsonObject);
		if (patchUtil(jsonObject)) {
//			System.out.println("LANDMARK9");
			JSONObject completeObj = read(planId);
//			System.out.println("complete json object");
//			System.out.println(completeObj);
			try {
				String newETag = etagManager.getETag(completeObj);
//				System.out.println("LANDMARK10");
				insert(completeObj);
//				System.out.println("LANDMARK11");
				return newETag;
			} catch (JSONException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return null;

	}
	
	public boolean patchUtil(JSONObject jsonObject) {
		try {
			Jedis jedis = pool.getResource();
			String uuid = jsonObject.getString("objectType") + SEP + jsonObject.getString("objectId");
			Map<String, String> simpleMap = jedis.hgetAll(uuid);
			if (simpleMap.isEmpty()) {
				simpleMap = new HashMap<String, String>();
			}

			//if (!doesKeyExist(uuid))
				//return false;
//			System.out.println("LANDMARK13");
			for (Object key : jsonObject.keySet()) {
				if (key.toString().equals("PlanID"))
					continue;
				String attributeKey = String.valueOf(key);
				Object attributeVal = jsonObject.get(String.valueOf(key));
				String edge = attributeKey;

				if (attributeVal instanceof JSONObject) {
//					System.out.println("LANDMARK14");
					JSONObject embdObject = (JSONObject) attributeVal;
//					System.out.println(embdObject);
					String setKey = uuid + SEP + edge;
					String embd_uuid = embdObject.get("objectType") + SEP + embdObject.getString("objectId");
					jedis.sadd(setKey, embd_uuid);
					patchUtil(embdObject);

				} else if (attributeVal instanceof JSONArray) {
//					System.out.println("LANDMARK15");
					JSONArray jsonArray = (JSONArray) attributeVal;
					Iterator<Object> jsonIterator = jsonArray.iterator();
					String setKey = uuid + SEP + edge;

					while (jsonIterator.hasNext()) {
						JSONObject embdObject = (JSONObject) jsonIterator.next();
						String embd_uuid = embdObject.get("objectType") + SEP + embdObject.getString("objectId");
						jedis.sadd(setKey, embd_uuid);
						patchUtil(embdObject);
					}

				} else {
//					System.out.println("LANDMARK16");
//					System.out.println(String.valueOf(attributeVal));
					simpleMap.put(attributeKey, String.valueOf(attributeVal));
					
				}
			}
			jedis.hmset(uuid, simpleMap);
			jedis.close();
			return true;

		} catch (JedisException e) {
			e.printStackTrace();
			return false;
		}
	}
	public boolean replace(JSONObject body) {
		
		try {
			return insert(body)!=null?true:false;
			
		}catch (Exception e) {
			e.printStackTrace();
			return false;
		}
		
	}
	
	public boolean delete(String body) {
//		System.out.println("body before parse");
//		System.out.println(body);
//		System.out.println("-------------------------");
		JSONObject json = new JSONObject(body);
//		System.out.println("body after parse");
//		System.out.println(json);
//		System.out.println("-------------------------");
		if (!json.has("objectType") || !json.has("objectId"))
			return false;
//      Elastic search entrance, for next assignment
//		  try {
//			ecClient.delete(new DeleteRequest("insurance","plan",json.getString("objectId") ));
//		} catch (JSONException | IOException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
		return deleteUtil(json.getString("objectType") + SEP + json.getString("objectId"));
	}

	public boolean deleteUtil(String uuid) {
		try {
			Jedis jedis = pool.getResource();

			
			Set<String> keys = jedis.keys(uuid + SEP + "*");
			if (keys.isEmpty()) {
//				System.out.println("keys is empty");
				return false;}
			for (String key : keys) {
				if (jedis.type(key).equalsIgnoreCase("string")) {
					jedis.del(key);
					continue;
				}
				Set<String> jsonKeySet = jedis.smembers(key);
				for (String embd_uuid : jsonKeySet) {
					deleteUtil(embd_uuid);
				}
				jedis.del(key);
			}

			jedis.del(uuid);
			jedis.close();
			return true;
		} catch (JedisException e) {
			e.printStackTrace();
//			System.out.println("exception happened");
			return false;
		}
	}

	public boolean doesKeyExist(String id) {

		try {
			Jedis jedis = pool.getResource();
			if (jedis.exists(id) || !jedis.keys(id + SEP + "*").isEmpty()) {
				jedis.close();
				return true;
			} else {
				jedis.close();
				return false;
			}
		} catch (JedisException e) {
			e.printStackTrace();
			return false;
		}
	}

}

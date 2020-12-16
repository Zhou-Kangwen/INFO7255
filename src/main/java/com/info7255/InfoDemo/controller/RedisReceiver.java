package com.info7255.InfoDemo.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;

import java.io.IOException;


@Service
public class RedisReceiver implements MessageListener {
  //  public static List<String> messageList = new ArrayList<String>();
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisReceiver.class);

    HomeController p = new HomeController();
    @Override
    public void onMessage(Message message, byte[] pattern) {
        // TODO Auto-generated method stub
       // System.out.println(message.toString());
        try {
            p.setIndex(message);
        } catch (IOException e) {
            e.printStackTrace();
        }
        LOGGER.info("Received data - " + message.toString() + " from Topic - " + new String(pattern));
    }
}
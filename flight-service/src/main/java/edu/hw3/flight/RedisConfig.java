package edu.hw3.flight;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@Configuration
public class RedisConfig {
  @Bean
  public LettuceConnectionFactory redisConnectionFactory(
      @Value("${redis.sentinel-master}") String master,
      @Value("${redis.sentinel-nodes}") String nodes) {
    RedisSentinelConfiguration c = new RedisSentinelConfiguration().master(master);
    for (String node : nodes.split(",")) {
      String[] hp = node.trim().split(":");
      c.sentinel(hp[0], Integer.parseInt(hp[1]));
    }
    return new LettuceConnectionFactory(c);
  }
}

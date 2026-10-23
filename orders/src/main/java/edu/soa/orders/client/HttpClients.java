package edu.soa.orders.client;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public final class HttpClients {
  private HttpClients() {}

  public static RestClient create(String url) {
    return create(url, Duration.ofMillis(700));
  }

  public static RestClient create(String url, Duration readTimeout) {
    var http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(readTimeout);
    return RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }
}

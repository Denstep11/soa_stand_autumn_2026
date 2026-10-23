package edu.soa.orders.config;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
public class DatabaseConfig {
  @Bean
  HikariDataSource dataSource(Environment env) {
    String url = env.getProperty("DATABASE_URL", "postgres://orders:orders@localhost:5432/orders");
    HikariDataSource ds = new HikariDataSource();
    if (url.startsWith("jdbc:")) {
      ds.setJdbcUrl(url);
      ds.setUsername(env.getProperty("spring.datasource.username", "orders"));
      ds.setPassword(env.getProperty("spring.datasource.password", "orders"));
    } else {
      URI uri = URI.create(url);
      String authority = uri.getRawAuthority();
      ds.setJdbcUrl(
          "jdbc:postgresql://"
              + authority.substring(authority.lastIndexOf('@') + 1)
              + uri.getRawPath()
              + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
      if (uri.getRawUserInfo() != null) {
        String[] credentials = uri.getRawUserInfo().split(":", 2);
        ds.setUsername(URLDecoder.decode(credentials[0], StandardCharsets.UTF_8));
        ds.setPassword(
            credentials.length > 1
                ? URLDecoder.decode(credentials[1], StandardCharsets.UTF_8)
                : "");
      }
    }
    ds.setMaximumPoolSize(16);
    ds.setConnectionTimeout(1000);
    return ds;
  }
}

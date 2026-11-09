package so.fox.nodechain;

import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;

// Главная точка входа ноды блокчейна NodeChain.
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class NodechainApplication {

  // Точка запуска Spring Boot приложения.
  public static void main(String[] args) {
    SpringApplication.run(NodechainApplication.class, args);
  }

  // Отключает опасное автоматическое усечение float чисел в целочисленные типы.
  @Bean
  public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
    return builder -> builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
  }
}

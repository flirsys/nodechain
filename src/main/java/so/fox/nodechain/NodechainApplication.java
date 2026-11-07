package so.fox.nodechain;

import com.fasterxml.jackson.databind.DeserializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;

// Главная точка входа ноды блокчейна NodeChain
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class NodechainApplication {

  private static final Logger log = LoggerFactory.getLogger(NodechainApplication.class);
  private static final Path SERVER_PROPERTIES_PATH = Path.of("server.properties");

  // Отключает опасное автоматическое усечение float чисел в целочисленные типы
  @Bean
  public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
    return builder -> builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
  }

  // Точка запуска Spring Boot приложения
  public static void main(String[] args) {
    ensureServerPropertiesExist();
    SpringApplication.run(NodechainApplication.class, args);
  }

  private static void ensureServerPropertiesExist() {
    if (Files.exists(SERVER_PROPERTIES_PATH)) return;

    String defaultContent = """
      # ===================================================================
      # NodeChain User Overrides
      # Add properties below to override default values from application.properties
      # e.g.:
      # server.port=8080
      # node.p2p.port=9090
      # node.p2p.seeds=127.0.0.1:9091
      # blockchain.genesis-allocation=1000000000000
      # blockchain.genesis-address=
      # blockchain.halving-interval=210000
      # ===================================================================
      """;
    try {
      Files.writeString(SERVER_PROPERTIES_PATH, defaultContent);
      log.info("Created default server.properties in current directory.");
    } catch (IOException err) {
      log.error("Failed to create default server.properties: {}", err.getMessage());
    }
  }
}

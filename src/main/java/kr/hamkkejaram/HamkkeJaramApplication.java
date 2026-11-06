package kr.hamkkejaram;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HamkkeJaramApplication {
    public static void main(String[] args) {
        SpringApplication.run(HamkkeJaramApplication.class, args);
    }
}

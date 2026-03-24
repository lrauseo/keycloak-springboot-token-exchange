package br.com.lrs.example2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class ExampleService2Application {

    public static void main(String[] args) {
        SpringApplication.run(ExampleService2Application.class, args);
    }
}

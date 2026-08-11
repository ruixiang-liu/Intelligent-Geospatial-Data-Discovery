package edu.psu.giscience.igdd.config;

import edu.psu.giscience.igdd.exception.Neo4jConnectionException;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Neo4jConfig {

    @Value("${neo4j.uri}")
    private String uri;

    @Value("${neo4j.username}")
    private String username;

    @Value("${neo4j.password}")
    private String password;

    @Bean
    public Driver neo4jDriver() {
        Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
        
        // Verify connectivity on startup
        try {
            driver.verifyConnectivity();
        } catch (Exception e) {
            driver.close();
            throw new Neo4jConnectionException(
                String.format("Failed to connect to Neo4j database. URI: %s, Username: %s. Please check if Neo4j service is running and connection configuration is correct.", 
                    uri, username), 
                e
            );
        }
        
        return driver;
    }
}

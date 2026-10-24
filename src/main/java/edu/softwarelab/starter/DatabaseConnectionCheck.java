package edu.softwarelab.starter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseConnectionCheck implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(DatabaseConnectionCheck.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseConnectionCheck(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer result =
                jdbcTemplate.queryForObject("SELECT 1", Integer.class);

        if (!Integer.valueOf(1).equals(result)) {
            throw new IllegalStateException(
                    "Unexpected database connection check result");
        }

        log.info("DATABASE CONNECTION CHECK PASSED");
    }
}
package com.warehouse.postgres;

import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Paths;

public final class PostgresTestImage {

    public static final DockerImageName IMAGE = DockerImageName.parse(
            new ImageFromDockerfile("warehouse-postgres-test", false)
                    .withFileFromPath(".", Paths.get("docker/postgres"))
                    .get()
    ).asCompatibleSubstituteFor("postgres");

    private PostgresTestImage() {
    }
}
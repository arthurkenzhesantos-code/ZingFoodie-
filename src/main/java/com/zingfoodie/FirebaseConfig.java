
package com.zingfoodie;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

@Configuration
public class FirebaseConfig {

    @Value("${FIREBASE_SERVICE_ACCOUNT_JSON:}")
    private String serviceAccountJson;

    @PostConstruct
    public void initializeFirebase() throws Exception {
        if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
            System.out.println(
                "Firebase credentials not configured; existing login remains available."
            );
            return;
        }

        if (!FirebaseApp.getApps().isEmpty()) {
            return;
        }

        GoogleCredentials credentials = GoogleCredentials.fromStream(
            new ByteArrayInputStream(
                serviceAccountJson.getBytes(StandardCharsets.UTF_8)
            )
        );

        FirebaseOptions options = FirebaseOptions.builder()
            .setCredentials(credentials)
            .setProjectId("zingfoodie-18e4e")
            .build();

        FirebaseApp.initializeApp(options);

        System.out.println("Firebase Admin SDK initialized successfully.");
    }
}

package com.processdesk;

import com.processdesk.ai.AiProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;

@SpringBootApplication
public class ProcessDeskApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcessDeskApplication.class, args);
    }

    /** Reports which provider is actually answering, so the UI never claims the wrong one. */
    @RestController
    static class HealthController {

        private final AiProvider ai;

        HealthController(AiProvider ai) {
            this.ai = ai;
        }

        @GetMapping("/api/health")
        Map<String, Object> health() {
            // usage is empty for providers that cost nothing to call, so the UI can show a
            // budget only when there is one to spend.
            return Map.of("ok", true, "provider", ai.name(), "usage", ai.usage());
        }
    }

    /**
     * The Vite dev server runs on a different port; allow it during development.
     *
     * <p>Any localhost port, not just 5173. Vite takes the next free one when 5173 is already
     * held — start a second dev server and it silently lands on 5174, where every request is
     * answered 403 by CORS. The browser reports that as a failure to reach the server, so the
     * backend looks down while it is running perfectly, and the port is nowhere in the message.
     * Pinning one port buys nothing: the origin is still only ever this machine.
     */
    @Bean
    WebMvcConfigurer corsConfig() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                        .allowedMethods("GET", "PUT", "POST");
            }
        };
    }
}

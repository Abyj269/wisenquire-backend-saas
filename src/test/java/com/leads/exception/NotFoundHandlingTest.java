package com.leads.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unknown paths (e.g. /swagger-ui.html with springdoc off in prod) must be 404, not a 500
 * from the Exception catch-all. Goes through real MVC exception resolution, so it also proves
 * the specific handler takes precedence over handleUnexpected.
 */
class NotFoundHandlingTest {

    @RestController
    static class Throwing {
        @GetMapping("/missing-resource")
        void resource() throws Exception {
            throw new NoResourceFoundException(HttpMethod.GET, "swagger-ui.html");
        }

        @GetMapping("/missing-handler")
        void handler() throws Exception {
            throw new NoHandlerFoundException("GET", "/v3/api-docs", new HttpHeaders());
        }

        @GetMapping("/boom")
        void boom() {
            throw new NullPointerException("internal detail");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Throwing())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void noResourceFoundIs404() throws Exception {
        mvc.perform(get("/missing-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Not found."));
    }

    @Test
    void noHandlerFoundIs404() throws Exception {
        mvc.perform(get("/missing-handler"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Not found."));
    }

    @Test
    void otherExceptionsStill500WithSafeMessage() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Something went wrong. Please try again."));
    }
}

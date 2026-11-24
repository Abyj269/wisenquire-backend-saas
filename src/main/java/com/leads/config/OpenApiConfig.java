package com.leads.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    /**
     * D12: every operation documents its error responses as ErrorResponse. (springdoc drops
     * component schemas nothing references, so the reference is what keeps them in the spec.)
     */
    @Bean
    public org.springdoc.core.customizers.GlobalOpenApiCustomizer errorResponseCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null) openApi.setComponents(new Components());
            // The error body and its closed code list: an API contract, so changing it is versioned.
            openApi.getComponents()
                    .addSchemas("ErrorCode", new io.swagger.v3.oas.models.media.StringSchema()
                            ._enum(java.util.Arrays.stream(com.leads.exception.ErrorCode.values()).map(Enum::name).toList())
                            .description("D12 error code. The frontend maps by code, never by message."))
                    .addSchemas("ErrorResponse", new io.swagger.v3.oas.models.media.ObjectSchema()
                            .description("Every error response (D12). `message` is kept for older clients.")
                            .addProperty("code", new io.swagger.v3.oas.models.media.Schema<String>().$ref("#/components/schemas/ErrorCode"))
                            .addProperty("message", new io.swagger.v3.oas.models.media.StringSchema())
                            .addProperty("details", new io.swagger.v3.oas.models.media.ObjectSchema())
                            .addProperty("traceId", new io.swagger.v3.oas.models.media.StringSchema())
                            .required(java.util.List.of("code", "message", "details")));
            if (openApi.getPaths() == null) return;
            var ref = new io.swagger.v3.oas.models.media.Schema<Object>().$ref("#/components/schemas/ErrorResponse");
            var content = new io.swagger.v3.oas.models.media.Content().addMediaType("application/json",
                    new io.swagger.v3.oas.models.media.MediaType().schema(ref));
            openApi.getPaths().values().forEach(item -> item.readOperations().forEach(op -> {
                if (op.getResponses() == null) op.setResponses(new io.swagger.v3.oas.models.responses.ApiResponses());
                op.getResponses().addApiResponse("default", new io.swagger.v3.oas.models.responses.ApiResponse()
                        .description("Error (D12): see ErrorCode for the closed list of codes").content(content));
            }));
        };
    }


    @Bean
    public OpenAPI customOpenAPI() {
        final String securitySchemeName = "bearerAuth";
        return new OpenAPI()
                .info(new Info()
                        .title("Enterprise Lead Management System (LMS) API")
                        .description("RESTful APIs for multi-tenant lead management, dynamic field engine, WhatsApp Cloud API live chat, and 4-tier RBAC.")
                        .version("1.0.0")
                        .contact(new Contact().name("Enterprise Architecture Team").email("architect@enterprise.io"))
                        .license(new License().name("Apache 2.0").url("http://springdoc.org")))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}

package com.leads.it.matrix;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Every application endpoint, from Spring MVC's own registry (not from a hand-kept list), as
 * {@code "METHOD /path"} keys without the {@code /api/v1} context path, e.g.
 * {@code "GET /broadcasts/{id}"}. Only handlers in {@code com.leads}: springdoc and Spring's
 * error controller are not ours. Actuator lives in a different handler mapping.
 */
public final class EndpointCatalog {

    private EndpointCatalog() { }

    public static Set<String> discover(RequestMappingHandlerMapping mapping) {
        return discoverWithControllers(mapping).keySet();
    }

    /** Endpoint key -> controller simple name (for the coverage report). */
    public static Map<String, String> discoverWithControllers(RequestMappingHandlerMapping mapping) {
        Map<String, String> keys = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
            if (!e.getValue().getBeanType().getPackageName().startsWith("com.leads")) continue;
            RequestMappingInfo info = e.getKey();
            Set<String> patterns = info.getPatternValues();
            var methods = info.getMethodsCondition().getMethods();
            String controller = e.getValue().getBeanType().getSimpleName();
            for (String pattern : patterns) {
                if (methods.isEmpty()) keys.put("ANY " + pattern, controller);
                else methods.forEach(m -> keys.put(m.name() + " " + pattern, controller));
            }
        }
        return keys;
    }
}

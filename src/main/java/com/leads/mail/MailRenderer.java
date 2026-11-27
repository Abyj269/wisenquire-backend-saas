package com.leads.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Module 02 P2 item 4: renders a {@link MailTemplate} to its HTML and plain-text versions with Thymeleaf
 * ({@code templates/email/<file>.html|.txt}). Values are escaped by Thymeleaf. {@code productName} is always set.
 */
@Component
public class MailRenderer {

    private final TemplateEngine html = engine(TemplateMode.HTML, ".html");
    private final TemplateEngine text = engine(TemplateMode.TEXT, ".txt");

    @Value("${app.mail.product-name:WiseNquire}")
    private String productName = "WiseNquire";

    private static TemplateEngine engine(TemplateMode mode, String suffix) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/email/");
        resolver.setSuffix(suffix);
        resolver.setTemplateMode(mode);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        TemplateEngine engine = new SpringTemplateEngine(); // SpEL (no OGNL on the classpath)
        engine.setTemplateResolver(resolver);
        return engine;
    }

    public MailProvider.OutgoingMail render(String to, MailTemplate template, Map<String, ?> variables) {
        for (String name : template.variables) {
            if (variables == null || variables.get(name) == null) throw new IllegalArgumentException(template + " needs " + name);
        }
        Map<String, Object> vars = new HashMap<>(variables);
        vars.put("productName", productName);
        Context ctx = new Context(Locale.ENGLISH, vars);
        return new MailProvider.OutgoingMail(to, template.subject, html.process(template.file, ctx), text.process(template.file, ctx));
    }
}

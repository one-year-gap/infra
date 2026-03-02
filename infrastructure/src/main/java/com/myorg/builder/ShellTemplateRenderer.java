package com.myorg.builder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShellTemplateRenderer {
    private ShellTemplateRenderer() {
    }
    // 템플릿에서 치환되지 않은 토큰(__TOKEN__)을 찾기 위한 정규식.
    private static final Pattern TEMPLATE_TOKEN_PATTERN = Pattern.compile("__([A-Z0-9_]+)__");


    // 템플릿을 치환 -> heredoc 기반의 파일 생성 커맨드 목록으로 변환
    public static List<String> writeToFileCommands(String templatePath, String targetPath, Map<String, String> values) {
        String renderedTemplate = renderTemplate(templatePath, values);
        String heredocDelimiter = "__HSC_TEMPLATE_EOF_" + Integer.toUnsignedString(targetPath.hashCode()) + "__";

        List<String> commands = new ArrayList<>();
        commands.add("cat <<'" + heredocDelimiter + "' >" + targetPath);
        commands.addAll(Arrays.asList(renderedTemplate.split("\\R", -1)));
        commands.add(heredocDelimiter);
        return commands;
    }

    // 템플릿 파일을 읽고 플레이스홀더를 실제 값으로 치환
    public static String renderTemplate(String templateResourcePath, Map<String, String> templateValues) {
        String renderedTemplate = load(templateResourcePath);

        for (Map.Entry<String, String> entry : templateValues.entrySet()) {
            renderedTemplate = renderedTemplate.replace("__" + entry.getKey() + "__", entry.getValue());
        }

        Matcher unresolvedToken = TEMPLATE_TOKEN_PATTERN.matcher(renderedTemplate);
        if (unresolvedToken.find()) {
            throw new IllegalStateException(
                    "Template placeholder is not resolved: "
                    + unresolvedToken.group()
                    + " in "
                    + templateResourcePath
            );
        }
        return renderedTemplate;
    }

    // classpath 리소스에서 템플릿 파일 내용 읽기
    public static String load(String templateResourcePath) {
        try (InputStream in = ShellTemplateRenderer.class.getClassLoader().getResourceAsStream(templateResourcePath)) {
            if (in == null) throw new IllegalStateException("Template resource not found: " + templateResourcePath);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read template resource: " + templateResourcePath, ex);
        }
    }
}

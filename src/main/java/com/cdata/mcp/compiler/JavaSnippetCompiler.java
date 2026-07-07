package com.cdata.mcp.compiler;

import javax.tools.*;
import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class JavaSnippetCompiler {

    public record CompileResult(boolean success, Path classDir, List<String> errors) {}

    public static CompileResult compile(String source, String className) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available. Run with a JDK, not a JRE.");
        }

        Path tmpDir = Files.createTempDirectory("jdbc-mcp-snippet-");
        // Source must match package path: com/cdata/mcp/snippets/ClassName.java
        Path pkgDir = tmpDir.resolve("com/cdata/mcp/snippets");
        Files.createDirectories(pkgDir);
        Path srcFile = pkgDir.resolve(className + ".java");
        Files.writeString(srcFile, source, StandardCharsets.UTF_8);

        StringWriter errWriter = new StringWriter();
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(srcFile.toFile());

        List<String> options = Arrays.asList("--release", "17", "-d", tmpDir.toString());
        JavaCompiler.CompilationTask task = compiler.getTask(errWriter, fm, null, options, null, units);
        boolean ok = task.call();
        fm.close();

        if (ok) {
            return new CompileResult(true, tmpDir, List.of());
        } else {
            // Clean up on failure
            deleteDir(tmpDir.toFile());
            String errText = errWriter.toString().trim();
            return new CompileResult(false, null, Arrays.asList(errText.split("\n")));
        }
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
        dir.delete();
    }
}

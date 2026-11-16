package org.cloide.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.impl.file.impl.JavaFileManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiJavaFile;
import com.intellij.ide.highlighter.JavaFileType;
import java.nio.charset.StandardCharsets;
import com.intellij.psi.search.GlobalSearchScope;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.cli.jvm.config.JvmContentRootsKt;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.config.JVMConfigurationKeys;

/** 每次调用只解析指定工作树。路径和行号对应返回的源码摘要。 */
public final class Symbols {
    public record Symbol(String kind, String name, String file, int line, String sourceSha256) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 35) {
            throw new IllegalArgumentException("用法：Symbols 工作区 完整类名[#方法名] 相对源码根目录...");
        }
        boolean single = args[0].equals("--file");
        Path root = single ? null : Path.of(args[0]).toRealPath();
        String[] query = args[1].split("#", -1);
        if (query.length > 2 || !query[0].matches("[A-Za-z_$][A-Za-z0-9_$.]{0,500}")
                || (query.length == 2 && !query[1].matches("[A-Za-z_$][A-Za-z0-9_$]{0,140}"))) {
            throw new IllegalArgumentException("类名或方法名不正确");
        }
        var settings = new CompilerConfiguration();
        settings.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.Companion.getNONE());
        settings.put(JVMConfigurationKeys.JDK_HOME, new File(System.getProperty("java.home")));
        for (int i = 2; !single && i < args.length; i++) {
            Path relative = Path.of(args[i]);
            Path source = root.resolve(relative).toRealPath();
            if (relative.isAbsolute() || !source.startsWith(root) || !Files.isDirectory(source)) {
                throw new IllegalArgumentException("源码目录不在工作区内");
            }
            JvmContentRootsKt.addJavaSourceRoot(settings, source.toFile());
        }
        var lifetime = Disposer.newDisposable("cloide-workspace-analysis");
        List<Symbol> matches = new ArrayList<>();
        try {
            var environment = KotlinCoreEnvironment.createForProduction(lifetime, settings, EnvironmentConfigFiles.JVM_CONFIG_FILES);
            var project = environment.getProject();
            if (single) {
                String name = args[2];
                if (args.length != 3 || !name.matches("[A-Za-z0-9_$.-]+\\.java")) throw new IllegalArgumentException("需要单个 Java 文件名");
                byte[] bytes = System.in.readNBytes(2 * 1024 * 1024 + 1);
                if (bytes.length > 2 * 1024 * 1024) throw new IllegalArgumentException("文件太大");
                var file = (PsiJavaFile) PsiFileFactory.getInstance(project).createFileFromText(
                        name, JavaFileType.INSTANCE, new String(bytes, StandardCharsets.UTF_8));
                String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                for (var type : file.getClasses()) match(type, query, name, file.getText(), sha, matches);
                System.out.println(new ObjectMapper().writeValueAsString(matches));
                return;
            }
            var type = JavaFileManager.getInstance(project).findClass(query[0], GlobalSearchScope.allScope(project));
            if (type != null && type.getContainingFile().getVirtualFile() != null) {
                Path file = Path.of(type.getContainingFile().getVirtualFile().getPath()).toRealPath();
                if (file.startsWith(root) && Files.size(file) <= 2 * 1024 * 1024) {
                    String path = root.relativize(file).toString().replace(File.separatorChar, '/');
                    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
                    String content = type.getContainingFile().getText();
                    match(type, query, path, content, sha, matches);
                }
            }
            System.out.println(new ObjectMapper().writeValueAsString(matches));
        } finally {
            Disposer.dispose(lifetime);
        }
    }

    private static void match(PsiClass type, String[] query, String path, String content, String sha, List<Symbol> matches) {
        if (query[0].equals(type.getQualifiedName())) {
            if (query.length == 1) matches.add(new Symbol("class",type.getQualifiedName(),path,line(content,type.getTextOffset()),sha));
            for (PsiMethod method : type.getMethods()) {
                if (query.length == 1 || method.getName().equals(query[1])) {
                    matches.add(new Symbol("method",method.getName() + method.getParameterList().getText(),
                            path,line(content,method.getTextOffset()),sha));
                }
            }
        } else {
            for (var nested : type.getInnerClasses()) match(nested,query,path,content,sha,matches);
        }
    }

    private static int line(String source, int offset) {
        return 1 + (int)source.substring(0,offset).chars().filter(c -> c == '\n').count();
    }
}

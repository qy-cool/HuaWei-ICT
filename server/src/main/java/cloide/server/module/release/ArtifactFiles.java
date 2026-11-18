package cloide.server.module.release;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Component;

/** 在同一文件系统原子移动非空目录，多个写入者不能覆盖已经发布的非空目录。 */
@Component
public class ArtifactFiles {
    private final Path root;
    private final long maxBytes;

    public ArtifactFiles(@Value("${cloide.artifacts.directory:./data/artifacts}") String directory,
                         @Value("${cloide.artifacts.max-bytes:134217728}") long maxBytes) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
        if (maxBytes <= 0) throw new IllegalArgumentException("artifact size limit must be positive");
        this.maxBytes = maxBytes;
    }

    public long put(String sha, InputStream input) throws IOException {
        Path target = path(sha);
        Files.createDirectories(target.getParent());
        Path staging = Files.createTempDirectory(target.getParent(), ".upload-");
        Path blob = staging.resolve("blob");
        try {
            MessageDigest digest = digest();
            long size = 0;
            byte[] buffer = new byte[65536];
            try (var outputChannel = java.nio.channels.FileChannel.open(blob, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE); var output = Channels.newOutputStream(outputChannel)) {
                for (int count; (count = input.read(buffer)) != -1;) {
                    size += count;
                    if (size > maxBytes) throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
                outputChannel.force(true);
            }
            if (size == 0 || !sha.equals(HexFormat.of().formatHex(digest.digest()))) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
            }
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException duplicate) {
                if (!Files.isRegularFile(target.resolve("blob"), LinkOption.NOFOLLOW_LINKS)) throw duplicate;
                try (var verified = open(sha, size).getInputStream()) {
                    // 使用同摘要的并发上传必须能重新校验原对象。
                }
            }
            return size;
        } finally {
            Files.deleteIfExists(blob);
            Files.deleteIfExists(staging);
        }
    }

    public InputStreamResource open(String sha, long size) throws IOException {
        var channel = java.nio.channels.FileChannel.open(path(sha).resolve("blob"), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            var input = Channels.newInputStream(channel);
            var digest = digest();
            if (channel.size() != size || size > maxBytes) throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
            try (var sink = new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest)) {
                input.transferTo(sink);
            }
            if (!sha.equals(HexFormat.of().formatHex(digest.digest()))) {
                throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
            }
            channel.position(0);
            return new InputStreamResource(input);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private Path path(String sha) {
        if (sha == null || !sha.matches("[0-9a-f]{64}")) throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        return root.resolve(sha.substring(0, 2)).resolve(sha);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

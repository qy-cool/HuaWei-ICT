package cloide.server.module.repository;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 源码 bundle 的 SHA-256 寻址存储。受管同步进程在根目录下的临时镜像完成生成，
 * 落位与读取都只经同文件系统原子移动，节点按摘要取回且可按 Range 复用分段。
 */
@Component
public class SourceBundleStore {
    private final Path root;
    private final long maxBytes;

    public SourceBundleStore(@Value("${cloide.source-sync.directory:./data/source-bundles}") String directory,
                             @Value("${cloide.source-sync.max-bytes:134217728}") long maxBytes) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
        if (maxBytes <= 0) throw new IllegalArgumentException("source bundle size limit must be positive");
        this.maxBytes = maxBytes;
    }

    /** 在同一文件系统创建受保护的临时裸镜像目录，供 fetch 与 bundle 生成使用。 */
    public Path newMirror() throws IOException {
        // createTempDirectory 不创建父目录：首次使用前显式建好存储根。
        Files.createDirectories(root);
        return Files.createTempDirectory(root, ".mirror-");
    }

    /** 把生成完的 bundle 原子落位到摘要地址；同摘要并发生成时保留先落位者。 */
    public long store(String sha, Path bundleFile) throws IOException {
        if (sha == null || !sha.matches("[0-9a-f]{64}")) throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        long size = Files.size(bundleFile);
        if (size <= 0 || size > maxBytes) throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        Path target = root.resolve(sha.substring(0, 2)).resolve(sha);
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return size;
        Files.createDirectories(target.getParent());
        try {
            Files.move(bundleFile, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException duplicate) {
            // 并发同摘要：另一方已落位即视为成功，未落位才上抛。
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw duplicate;
        }
        return size;
    }

    /** 打开已落位 bundle 的只读路径；不存在或摘要格式非法按未找到处理。 */
    public Path open(String sha) {
        if (sha == null || !sha.matches("[0-9a-f]{64}")) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        Path target = root.resolve(sha.substring(0, 2)).resolve(sha);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return target;
    }

    public long maxBytes() {
        return maxBytes;
    }
}

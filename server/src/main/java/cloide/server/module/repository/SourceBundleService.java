package cloide.server.module.repository;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.RefNotAdvertisedException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.BundleWriter;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A2 受管同步进程：按 GitHub installation 领取的只读权限从受信 Git 出口按精确提交
 * fetch 到受保护临时裸镜像，把私有 ref 固定到该提交并逐字核对 commit 与 tree 后，
 * 生成只含该快照可达历史的 Git bundle。长期凭据只存在于本进程内存，不进入日志，
 * 也不向下游节点传递。
 */
@Component
public class SourceBundleService {
    private final GitHubApp app;
    private final SourceBundleStore store;
    private final URI gitOrigin;

    public SourceBundleService(GitHubApp app, SourceBundleStore store,
            @Value("${cloide.github.git-origin:https://github.com}") URI gitOrigin) {
        boolean loopback = "http".equals(gitOrigin.getScheme())
                && ("127.0.0.1".equals(gitOrigin.getHost()) || "localhost".equals(gitOrigin.getHost()));
        // file:// 仅供企业内网挂载的受信 Git 镜像与回环测试使用；其余形态必须是 HTTPS 主机根。
        boolean mounted = "file".equals(gitOrigin.getScheme()) && gitOrigin.getHost() == null;
        if ((!loopback && !mounted && !"https".equals(gitOrigin.getScheme())) || gitOrigin.getHost() == null && !mounted
                || gitOrigin.getUserInfo() != null || gitOrigin.getQuery() != null || gitOrigin.getFragment() != null
                || !"file".equals(gitOrigin.getScheme()) && !gitOrigin.getPath().replaceAll("/$", "").isEmpty()) {
            throw new IllegalArgumentException("Git origin must be a trusted HTTPS host root, loopback test service or mounted file root");
        }
        this.app = app;
        this.store = store;
        this.gitOrigin = gitOrigin;
    }

    public record Bundle(String sha256, long byteCount) { }

    /**
     * 生成快照 bundle。远端无法证明固定提交来源（强推、删分支或上游清理，按 SHA fetch
     * 无通告）抛 SOURCE_REF_NOT_FOUND，供状态机落 source_unavailable 终态；其余传输
     * 失败按暂时不可用上抛供重试。镜像目录与未落位文件在所有路径上清理。
     */
    public Bundle produce(SourceIdentityService.Snapshot snapshot, RepositoryService.ActiveBinding binding) {
        String token = app.token(Map.of("contents", "read"), binding.remoteId(), null);
        Path mirror = null;
        try {
            mirror = store.newMirror();
            return fetchAndBundle(snapshot, binding, token, mirror);
        } catch (BusinessException e) {
            throw e;
        } catch (RefNotAdvertisedException | MissingObjectException e) {
            // 上游无法证明固定提交来源：分支被删（未通告）或提交对象不可达（强推/清理）。
            throw new BusinessException(ErrorCode.SOURCE_REF_NOT_FOUND, e);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.GITHUB_API_UNAVAILABLE, e);
        } finally {
            if (mirror != null) deleteRecursively(mirror);
        }
    }

    private Bundle fetchAndBundle(SourceIdentityService.Snapshot snapshot, RepositoryService.ActiveBinding binding,
            String token, Path mirror) throws Exception {
        String headRef = "refs/cloide/heads/" + snapshot.snapshotId();
        String privateRef = "refs/cloide/snapshots/" + snapshot.snapshotId();
        try (Git git = Git.init().setBare(true).setDirectory(mirror.toFile()).call()) {
            Repository repo = git.getRepository();
            // 按快照来源 ref fetch 到临时名：分支前移后 fetch 落的是新 tip，历史提交
            // 通常仍可达；强推、删分支或上游清理后不可达即 source_unavailable。
            git.fetch().setRemote(gitOrigin + "/" + binding.ownerName() + "/" + binding.repoName() + ".git")
                    .setRefSpecs(new RefSpec("+" + snapshot.ref() + ":" + headRef))
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", token))
                    .setTimeout(120).call();
            ObjectId commitId = ObjectId.fromString(snapshot.commitSha());
            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit commit = walk.parseCommit(commitId);
                // 与 A1 快照记录的树逐字核对：上游对象与快照事实不符时拒绝出包。
                if (!commit.getTree().getName().equals(snapshot.treeSha())) {
                    throw new BusinessException(ErrorCode.SOURCE_REQUEST_CONFLICT,
                            Map.of("check","tree","expected",snapshot.treeSha(),"actual",commit.getTree().getName()));
                }
            }
            // 私有 ref 由本进程固定到快照提交：bundle 中钉住的正是被批准的那份代码。
            RefUpdate pin = repo.updateRef(privateRef);
            pin.setNewObjectId(commitId);
            RefUpdate.Result pinned = pin.update();
            if (pinned != RefUpdate.Result.NEW && pinned != RefUpdate.Result.NO_CHANGE
                    && pinned != RefUpdate.Result.FORCED) {
                throw new BusinessException(ErrorCode.SOURCE_REQUEST_CONFLICT, Map.of("check","pin","result",pinned.name()));
            }
            Path bundleFile = mirror.resolve("bundle.git");
            try (OutputStream out = Files.newOutputStream(bundleFile)) {
                BundleWriter writer = new BundleWriter(repo);
                writer.include(privateRef, commitId);
                writer.writeBundle(NullProgressMonitor.INSTANCE, out);
            }
            String sha = sha256(bundleFile);
            return new Bundle(sha, store.store(sha, bundleFile));
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1; ) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteRecursively(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 清理失败不掩盖主流程结果；残留的隐藏临时目录由运维按保留策略回收。
                }
            });
        } catch (Exception ignored) {
            // 同上：镜像目录创建失败或已消失时无需处理。
        }
    }
}

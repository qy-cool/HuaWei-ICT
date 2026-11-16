import { execFile } from "node:child_process";
import { tmpdir } from "node:os";
import path from "node:path";

// classpath 与 JVM 来自节点管理员配置。分析器只收到本次授权文件的内容。
export function queryJava(config, file, symbol, snapshot, signal) {
  if (!file.endsWith(".java") || snapshot.content === null) throw new Error("expected a readable Java source file");
  if (!/^[A-Za-z_$][A-Za-z0-9_$.]{0,500}(#[A-Za-z_$][A-Za-z0-9_$]{0,140})?$/.test(symbol)) throw new Error("invalid Java symbol");
  if (!path.isAbsolute(config.java) || !Array.isArray(config.classpath) || !config.classpath.length ||
      !config.classpath.every(entry => path.isAbsolute(entry) && !entry.includes(path.delimiter))) {
    throw new Error("Java analysis requires an administrator-provided JVM and absolute classpath");
  }
  return new Promise((resolve, reject) => {
    const base = path.basename(file);
    const child = execFile(config.java, ["-Xmx384m","-Djava.awt.headless=true","-cp",config.classpath.join(path.delimiter),
      "org.cloide.analysis.Symbols","--file",symbol,base], {cwd:tmpdir(), windowsHide:true, signal, timeout:15000,
      maxBuffer:256*1024, env:{PATH:process.env.PATH, SystemRoot:process.env.SystemRoot, LANG:"C.UTF-8"}}, (err,stdout) => {
      if (err) { reject(new Error("Java analysis did not complete")); return; }
      try {
        const hits = JSON.parse(stdout);
        if (!Array.isArray(hits) || hits.some(hit => hit.sourceSha256 !== snapshot.hash || hit.file !== base)) {
          throw new Error("Java result does not describe the current source");
        }
        resolve(hits.map(hit => ({...hit,file})));
      } catch (error) { reject(error); }
    });
    child.stdin.on("error", error => { if (error.code !== "EPIPE") reject(error); });
    child.stdin.end(snapshot.content,"utf8");
  });
}

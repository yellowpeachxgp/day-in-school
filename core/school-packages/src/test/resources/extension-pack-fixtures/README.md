# extension-pack-fixtures：云端 dis-plugin pack 产出的 ZIP 校验联动夹具

> W6-01b / AC-42。本目录的夹具由云端 `cloud-in-school` 仓库 `packages/plugin-sdk` 的
> `dis-plugin` CLI 真实产出，用于验证「pack 生成的 ZIP 可被 Android 的校验链消费」。
> 完整的 Android 安装链路（原子写入、授权登记、数据库）属于 W6-01a，不在本夹具断言范围。

## 文件

- `org.example.pack-fixture-1.0.0.zip.base64`：`dis-plugin pack` 产出的确定性 ZIP 的 base64 单行文本。
  内容：`manifest.json`（card 模板、Ed25519 已签名）、`plugin-signing-public.key`（发布者公钥，自声明性质）、
  `dist/index.mjs` 与 `assets/card-data.json` 两个声明产物。
  打包时 ZIP SHA-256：`cdf3de1f029aba9fd323414c9f0c999e11c21f6f0c379a980ec8504c3e038190`（1779 字节）。

## 再生成方法（需要云端仓库源码与 Node ≥ 22，全部在 <devbox> 或本地开发机执行）

```bash
export DIS_PLUGIN_HOME=$(mktemp -d)
node packages/plugin-sdk/bin/dis-plugin.mjs keygen --id org.example.pack-fixture
node packages/plugin-sdk/bin/dis-plugin.mjs init /tmp/pack-fixture-proj --id org.example.pack-fixture --template card --keyring
node packages/plugin-sdk/bin/dis-plugin.mjs pack /tmp/pack-fixture-proj --out /tmp/pack-fixture.zip
base64 -w0 /tmp/pack-fixture.zip   # 写入本目录同名文件
```

注意：每次再生成都会产生新的密钥对，因此字节内容会变化；确定性保证是「同一内容两次打包字节相同」，
而不是「跨密钥生成不变」。测试不依赖固定的公钥或签名值，只依赖结构与校验链结论。

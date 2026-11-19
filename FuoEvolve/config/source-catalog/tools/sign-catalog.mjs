#!/usr/bin/env node
/**
 * 音源目录签名 / 验签工具（仓库外运行，不参与 App 构建）。
 *
 * 为什么放在 Node 里而不是 App 内：签名需要私钥，私钥绝不能进 APK。
 * App 只内置公钥做验签（provider/sourceupdate 的纯 Kotlin Ed25519 实现）。
 *
 * 用法：
 *   node sign-catalog.mjs --sign   --key <私钥种子hex> --catalog source-catalog.json
 *   node sign-catalog.mjs --verify --pub <公钥hex>     --catalog source-catalog.json
 *
 * 签名对象是**目录文件的原始字节**，结果写进 `<catalog>.sig`（Base64，单行）。
 * 因此改动目录后必须重新签名，且提交时不要让 git 改动换行符（保持 LF）。
 */
import { createPrivateKey, createPublicKey, sign, verify } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';

const args = process.argv.slice(2);
const mode = args.includes('--sign') ? 'sign' : args.includes('--verify') ? 'verify' : null;
if (!mode) {
  console.error('用法: --sign | --verify，并配合 --catalog <file>');
  process.exit(2);
}

function argOf(name) {
  const index = args.indexOf(name);
  return index >= 0 ? args[index + 1] : undefined;
}

const catalogPath = argOf('--catalog') ?? 'source-catalog.json';
const catalogBytes = readFileSync(catalogPath);

if (mode === 'sign') {
  const seedHex = argOf('--key') ?? process.env.FUOEVOLVE_CATALOG_KEY;
  if (!seedHex || seedHex.length !== 64) {
    console.error('缺少 --key <64 位十六进制私钥种子>（或用环境变量 FUOEVOLVE_CATALOG_KEY）');
    process.exit(2);
  }
  const pkcs8 = Buffer.concat([
    Buffer.from('302e020100300506032b657004220420', 'hex'),
    Buffer.from(seedHex, 'hex'),
  ]);
  const privateKey = createPrivateKey({ key: pkcs8, format: 'der', type: 'pkcs8' });
  const signature = sign(null, catalogBytes, privateKey);
  writeFileSync(`${catalogPath}.sig`, `${signature.toString('base64')}\n`);
  const publicKeyHex = createPublicKey(privateKey).export({ format: 'der', type: 'spki' })
    .subarray(-32).toString('hex');
  console.log(`已签名 ${catalogPath}`);
  console.log(`  .sig   -> ${catalogPath}.sig`);
  console.log(`  公钥    -> ${publicKeyHex}`);
  console.log('  记得把公钥同步进 SourceUpdateDefaults.TRUSTED_CATALOG_KEYS');
} else {
  const pubHex = argOf('--pub');
  if (!pubHex || pubHex.length !== 64) {
    console.error('缺少 --pub <64 位十六进制公钥>');
    process.exit(2);
  }
  const spki = Buffer.concat([
    Buffer.from('302a300506032b6570032100', 'hex'),
    Buffer.from(pubHex, 'hex'),
  ]);
  const publicKey = createPublicKey({ key: spki, format: 'der', type: 'spki' });
  const signature = Buffer.from(readFileSync(`${catalogPath}.sig`, 'utf8').trim(), 'base64');
  const ok = verify(null, catalogBytes, publicKey, signature);
  console.log(ok ? '验签通过' : '验签失败');
  process.exit(ok ? 0 : 1);
}

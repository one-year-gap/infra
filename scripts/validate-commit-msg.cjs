const fs = require("fs");

const msgFile = process.argv[2];
const firstLine = fs.readFileSync(msgFile, "utf8").split("\n")[0].trim();

if (!firstLine || firstLine.startsWith("Merge")) process.exit(0);

const ok = /^([a-z]+)\/([A-Z]+-\d+):\s+.+$/.test(firstLine);

if (!ok) {
  console.error(`최종 커밋 메시지 형식 위반: "${firstLine}"`);
  console.error(`Expected: <type>/<TICKET>: <message>  (예: feat/HSC-01: 로그인 API 추가)`);
  process.exit(1);
}

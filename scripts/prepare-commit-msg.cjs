const fs = require("fs");
const { execSync } = require("child_process");

const msgFile = process.argv[2];
const source = process.argv[3] || ""; // merge/squash 등
const msg = fs.readFileSync(msgFile, "utf8");

if (!msg || source === "merge" || source === "squash") process.exit(0);

const firstLine = msg.split("\n")[0].trim();
if (!firstLine) process.exit(0);

const branch = execSync("git branch --show-current").toString().trim();
const m = branch.match(/^([a-z]+)\/([A-Z]+-\d+)$/);


const branchType = m[1];
const ticket = m[2];

const mm = firstLine.match(/^([a-z]+)\s*:\s*(.+)$/);


const rest = mm[2];



const expectedPrefix = `${branchType}/${ticket}: `;
if (firstLine.startsWith(expectedPrefix)) process.exit(0);

const lines = msg.split("\n");
lines[0] = `${branchType}/${ticket}: ${rest}`;
fs.writeFileSync(msgFile, lines.join("\n"), "utf8");

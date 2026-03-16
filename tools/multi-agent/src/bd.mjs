import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

export async function readIssue(repoRoot, issueId) {
  const { stdout } = await execFileAsync("bd", ["show", issueId, "--json"], { cwd: repoRoot });
  const parsed = JSON.parse(stdout);
  if (!Array.isArray(parsed) || parsed.length === 0) {
    throw new Error(`bd issue not found: ${issueId}`);
  }
  return parsed[0];
}

export async function createFollowUpIssue(repoRoot, parentIssueId, followUp) {
  const args = [
    "create",
    followUp.title,
    `--description=${followUp.description}`,
    "-t",
    followUp.issueType,
    "-p",
    String(followUp.priority),
    "--deps",
    `discovered-from:${parentIssueId}`,
    "--json"
  ];
  const { stdout } = await execFileAsync("bd", args, { cwd: repoRoot });
  const parsed = JSON.parse(stdout);
  return parsed.id;
}

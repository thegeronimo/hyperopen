import fs from "node:fs/promises";

function insertIntoSection(content, heading, text) {
  const marker = `## ${heading}\n`;
  const start = content.indexOf(marker);
  if (start === -1) {
    return content;
  }
  const sectionStart = start + marker.length;
  const nextSectionOffset = content.slice(sectionStart).search(/\n## /);
  const insertionPoint =
    nextSectionOffset === -1 ? content.length : sectionStart + nextSectionOffset;
  return `${content.slice(0, insertionPoint)}\n${text}${content.slice(insertionPoint)}`;
}

export async function appendExecPlanProgress(execPlanPath, line) {
  const content = await fs.readFile(execPlanPath, "utf8");
  const updated = insertIntoSection(content, "Progress", `${line}\n`);
  await fs.writeFile(execPlanPath, updated, "utf8");
}

export async function appendExecPlanOutcome(execPlanPath, paragraph) {
  const content = await fs.readFile(execPlanPath, "utf8");
  const updated = insertIntoSection(content, "Outcomes & Retrospective", `\n${paragraph}\n`);
  await fs.writeFile(execPlanPath, updated, "utf8");
}

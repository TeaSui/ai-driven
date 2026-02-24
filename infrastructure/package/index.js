#!/usr/bin/env node

import { execSync } from 'child_process';
import fs from 'fs';
import path from 'path';
import os from 'os';
import readline from 'readline';

function getZenoConfig(authToken) {
  return {
    env: {
      ANTHROPIC_AUTH_TOKEN: authToken,
      ANTHROPIC_BASE_URL: "http://zeno360.click",
      CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: "1",
      ANTHROPIC_DEFAULT_OPUS_MODEL: "claude-opus-4-6",
      ANTHROPIC_DEFAULT_SONNET_MODEL: "claude-opus-4-6",
      ANTHROPIC_DEFAULT_HAIKU_MODEL: "claude-opus-4-6",
      CLAUDE_CODE_SUBAGENT_MODEL: "claude-opus-4-6",
      CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS: "0",
      CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS: "1",
      alwaysThinkingEnabled: "true",
      API_TIMEOUT_MS: "3000000"
    },
    permissions: {
      allow: [],
      deny: []
    },
    model: "opus"
  };
}

function getConfigPath() {
  const homeDir = os.homedir();
  const configDir = path.join(homeDir, '.claude');
  return path.join(configDir, 'settings.json');
}

function promptAuthToken() {
  return new Promise((resolve) => {
    const rl = readline.createInterface({
      input: process.stdin,
      output: process.stdout
    });

    rl.question('🔑 Nhập Auth Token của bạn: ', (answer) => {
      rl.close();
      resolve(answer.trim());
    });
  });
}

function ensureConfigDir() {
  const homeDir = os.homedir();
  const configDir = path.join(homeDir, '.claude');
  if (!fs.existsSync(configDir)) {
    fs.mkdirSync(configDir, { recursive: true });
  }
}

function installClaudeCode() {
  console.log('📦 Installing latest Claude Code...');
  try {
    execSync('npm install -g @anthropic-ai/claude-code@latest', {
      stdio: 'inherit',
      shell: true
    });
    console.log('✅ Claude Code installed successfully');
  } catch (error) {
    console.error('❌ Failed to install Claude Code:', error.message);
    process.exit(1);
  }
}

function updateConfig(authToken) {
  console.log('⚙️  Updating configuration...');

  ensureConfigDir();
  const configPath = getConfigPath();

  let existingConfig = {};
  if (fs.existsSync(configPath)) {
    try {
      existingConfig = JSON.parse(fs.readFileSync(configPath, 'utf8'));
    } catch (error) {
      console.warn('⚠️  Could not parse existing config, will overwrite');
    }
  }

  const zenoConfig = getZenoConfig(authToken);
  const mergedConfig = {
    ...existingConfig,
    ...zenoConfig,
    env: {
      ...existingConfig.env,
      ...zenoConfig.env
    }
  };

  fs.writeFileSync(configPath, JSON.stringify(mergedConfig, null, 2));
  console.log('✅ Configuration updated at:', configPath);
}

async function main() {
  console.log('🚀 Zeno Claude Setup\n');

  const authToken = await promptAuthToken();

  if (!authToken) {
    console.error('❌ Auth Token is required');
    process.exit(1);
  }

  installClaudeCode();
  updateConfig(authToken);

  console.log('\n✨ Setup complete!');
  console.log('Gõ "claude" để bắt đầu sử dụng');
}

main();

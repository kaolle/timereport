import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
    testDir: './tests/web',
    testMatch: '**/*.spec.js',
    fullyParallel: false,
    workers: 1,
    timeout: 30000,
    use: { ...devices['Pixel 7'], locale: 'sv-SE', baseURL: 'http://127.0.0.1:4173', trace: 'retain-on-failure' },
    webServer: { command: 'node tests/web/server.mjs', url: 'http://127.0.0.1:4173', reuseExistingServer: false },
    reporter: 'list'
});

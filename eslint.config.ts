import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import prettier from 'eslint-plugin-prettier';
import { defineConfig } from 'eslint/config';

export default defineConfig([
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.ts', '**/*.tsx'],
    plugins: {
      prettier,
    },
    rules: {
      'prettier/prettier': 'error',
      'no-unused-vars': 'off',
      '@typescript-eslint/no-unused-vars': [
        'error',
        {
          args: 'none',
          argsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
          caughtErrorsIgnorePattern: '^_',
        },
      ],
    },
  },
  {
    ignores: [
      'node_modules/**',
      'dist/**',
      'build/**',
      'lib/**',
      '**/*.{js,cjs,mjs}',
    ],
  },
]);

import type { Config } from 'jest';

export default {
  verbose: true,
  preset: '@react-native/jest-preset',
  modulePathIgnorePatterns: [
    '<rootDir>/example/node_modules',
    '<rootDir>/lib/',
  ],
  moduleNameMapper: {
    // force ./example/src files to use react/react-native versions of the root folder (jest-configured versions)
    '^react$': '<rootDir>/node_modules/react',
    '^react/(.*)': '<rootDir>/node_modules/react/$1',
    '^react-native$': '<rootDir>/node_modules/react-native',
    '^react-native/(.*)': '<rootDir>/node_modules/react-native/$1',
    // map the local library to source so tests under ./example can import it
    '^@pinge/react-native-steps$': '<rootDir>/src/index.tsx',
  },
} satisfies Config;

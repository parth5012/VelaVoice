// Test stub for react-native: ModelManager destructures NativeModules at
// module load; the real entry is Flow syntax that tsx/esbuild cannot parse
// in Node, so it must never resolve to node_modules under test.
module.exports = {
  NativeModules: {
    ModelVerifier: {},
  },
};

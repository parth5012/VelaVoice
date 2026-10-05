function buildEditTable(a, b, equals) {
    const m = a.length;
    const n = b.length;
    const dp = [];
    for (let i = 0; i <= m; i++) {
        dp[i] = [];
        dp[i][0] = i;
    }
    for (let j = 0; j <= n; j++) {
        dp[0][j] = j;
    }
    for (let i = 1; i <= m; i++) {
        for (let j = 1; j <= n; j++) {
            if (equals(a[i - 1], b[j - 1])) {
                dp[i][j] = dp[i - 1][j - 1];
            }
            else {
                dp[i][j] = Math.min(dp[i - 1][j] + 1, // deletion
                dp[i][j - 1] + 1, // insertion
                dp[i - 1][j - 1] + 1 // substitution
                );
            }
        }
    }
    return dp;
}
export function calculateEditDistance(original, corrected) {
    const dp = buildEditTable(original.split(''), corrected.split(''), (a, b) => a === b);
    return dp[original.length][corrected.length];
}
export function getEdits(original, corrected) {
    const A = original.trim().split(/\s+/).filter(Boolean);
    const B = corrected.trim().split(/\s+/).filter(Boolean);
    const dp = buildEditTable(A, B, (a, b) => a === b);
    const m = A.length;
    const n = B.length;
    const ops = [];
    let i = m;
    let j = n;
    while (i > 0 || j > 0) {
        if (i > 0 && j > 0 && A[i - 1] === B[j - 1]) {
            i--;
            j--;
        }
        else if (i > 0 && j > 0 && dp[i][j] === dp[i - 1][j - 1] + 1) {
            ops.unshift({
                type: 'substitution',
                original: A[i - 1],
                corrected: B[j - 1],
                position: i - 1
            });
            i--;
            j--;
        }
        else if (i > 0 && (j === 0 || dp[i][j] === dp[i - 1][j] + 1)) {
            ops.unshift({
                type: 'deletion',
                original: A[i - 1],
                corrected: '',
                position: i - 1
            });
            i--;
        }
        else if (j > 0 && (i === 0 || dp[i][j] === dp[i][j - 1] + 1)) {
            ops.unshift({
                type: 'insertion',
                original: '',
                corrected: B[j - 1],
                position: i
            });
            j--;
        }
    }
    return ops;
}

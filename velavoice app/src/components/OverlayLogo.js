import React from 'react';
import { View, StyleSheet } from 'react-native';
/**
 * Clean minimal "V" mark for the Vela Voice overlay.
 *
 * Uses two rounded bars rotated outward to form a V shape.
 * transformOrigin lets each arm pivot from its bottom-center,
 * creating a precise joined apex.
 */
const OverlayLogo = ({ size = 40, color = '#62f9ee', strokeRatio = 0.1, }) => {
    const stroke = Math.max(3, Math.round(size * strokeRatio));
    const armLength = Math.round(size * 0.6);
    const apexGap = 2; // distance from bottom edge
    const angle = '30deg';
    // Center each bar so they meet at the bottom-middle of the container
    const centerOffset = Math.round((size - stroke) / 2);
    return (<View style={[styles.container, { width: size, height: size }]}>
      {/* Left arm — rotates counter-clockwise from bottom center */}
      <View style={[
            styles.arm,
            {
                width: stroke,
                height: armLength,
                backgroundColor: color,
                borderRadius: stroke / 2,
                bottom: apexGap,
                left: centerOffset,
                transformOrigin: 'bottom center',
                transform: [{ rotate: `-${angle}` }],
            },
        ]}/>
      {/* Right arm — rotates clockwise from bottom center */}
      <View style={[
            styles.arm,
            {
                width: stroke,
                height: armLength,
                backgroundColor: color,
                borderRadius: stroke / 2,
                bottom: apexGap,
                left: centerOffset,
                transformOrigin: 'bottom center',
                transform: [{ rotate: angle }],
            },
        ]}/>
    </View>);
};
const styles = StyleSheet.create({
    container: {
        position: 'relative',
        alignItems: 'center',
        justifyContent: 'center',
    },
    arm: {
        position: 'absolute',
    },
});
export default OverlayLogo;

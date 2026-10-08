/**
 * Module: src/components/OverlayLogo
 * Intent: Brand V mark for the Vela Voice overlay.
 * Responsibilities: Size/color-parameterized logo rendering only.
 * Public API: default OverlayLogo({ size, color, strokeRatio })
 * Invariants: Pure render, no I/O, no side effects.
 * Side Effects: none
 * Maintenance: Update this block when exports, invariants, side effects, or ownership change.
 */
import React from 'react';
import { View, StyleSheet, type ViewStyle } from 'react-native';

interface OverlayLogoProps {
  /** Total size of the logo square (default: 40) */
  size?: number;
  /** Stroke color (default: brand neon cyan #62f9ee) */
  color?: string;
  /** Stroke thickness factor relative to size (default: 0.1) */
  strokeRatio?: number;
}

/**
 * RN ViewStyle historically lacks transformOrigin typing across versions.
 * Narrow extension keeps the cast explicit instead of `any`.
 */
type ArmStyleWithOrigin = ViewStyle & { transformOrigin: string };
const OverlayLogo: React.FC<OverlayLogoProps> = ({
  size = 40,
  color = '#62f9ee',
  strokeRatio = 0.1,
}) => {
  const stroke = Math.max(3, Math.round(size * strokeRatio));
  const armLength = Math.round(size * 0.6);
  const apexGap = 2; // distance from bottom edge
  const angle = '30deg';

  // Center each bar so they meet at the bottom-middle of the container
  const centerOffset = Math.round((size - stroke) / 2);

  return (
    <View style={[styles.container, { width: size, height: size }]}>
      {/* Left arm — rotates counter-clockwise from bottom center to form V apex */}
      <View
        style={[
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
          } as ArmStyleWithOrigin,
        ]}
      />
      {/* Right arm — rotates clockwise from bottom center */}
      <View
        style={[
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
          } as ArmStyleWithOrigin,
        ]}
      />
    </View>
  );
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

/* eslint-disable react-hooks/refs */
import React, { useEffect, useRef } from 'react';
import {
  View,
  Text,
  StyleSheet,
  Animated,
  Dimensions,
  Easing,
  StatusBar
} from 'react-native';

const { width, height } = Dimensions.get('window');

interface CinematicSplashProps {
  onFinish?: () => void;
}

export const CinematicSplash: React.FC<CinematicSplashProps> = ({ onFinish }) => {
  // Animation values with useNativeDriver: true for GPU acceleration
  const pulseRing1 = useRef(new Animated.Value(0.4)).current;
  const pulseRing2 = useRef(new Animated.Value(0.2)).current;
  const ringOpacity1 = useRef(new Animated.Value(0.9)).current;
  const ringOpacity2 = useRef(new Animated.Value(0.6)).current;

  const logoScale = useRef(new Animated.Value(0.75)).current;
  const logoOpacity = useRef(new Animated.Value(0)).current;

  const sweepAnim = useRef(new Animated.Value(-width * 0.8)).current;
  const progressAnim = useRef(new Animated.Value(0)).current;

  const containerScale = useRef(new Animated.Value(1)).current;
  const containerOpacity = useRef(new Animated.Value(1)).current;

  useEffect(() => {
    // 1. Radar pulse rings loop
    Animated.loop(
      Animated.parallel([
        Animated.sequence([
          Animated.timing(pulseRing1, {
            toValue: 2.2,
            duration: 1600,
            easing: Easing.out(Easing.cubic),
            useNativeDriver: true
          }),
          Animated.timing(pulseRing1, {
            toValue: 0.4,
            duration: 0,
            useNativeDriver: true
          })
        ]),
        Animated.sequence([
          Animated.timing(ringOpacity1, {
            toValue: 0,
            duration: 1600,
            easing: Easing.out(Easing.quad),
            useNativeDriver: true
          }),
          Animated.timing(ringOpacity1, {
            toValue: 0.9,
            duration: 0,
            useNativeDriver: true
          })
        ])
      ])
    ).start();

    // 2. Pulse Ring 2 staggered
    setTimeout(() => {
      Animated.loop(
        Animated.parallel([
          Animated.sequence([
            Animated.timing(pulseRing2, {
              toValue: 2.4,
              duration: 1600,
              easing: Easing.out(Easing.cubic),
              useNativeDriver: true
            }),
            Animated.timing(pulseRing2, {
              toValue: 0.2,
              duration: 0,
              useNativeDriver: true
            })
          ]),
          Animated.sequence([
            Animated.timing(ringOpacity2, {
              toValue: 0,
              duration: 1600,
              easing: Easing.out(Easing.quad),
              useNativeDriver: true
            }),
            Animated.timing(ringOpacity2, {
              toValue: 0.6,
              duration: 0,
              useNativeDriver: true
            })
          ])
        ])
      ).start();
    }, 400);

    // 3. Logo entrance & anamorphic sweep
    Animated.parallel([
      Animated.timing(logoScale, {
        toValue: 1,
        duration: 800,
        easing: Easing.out(Easing.back(1.5)),
        useNativeDriver: true
      }),
      Animated.timing(logoOpacity, {
        toValue: 1,
        duration: 600,
        useNativeDriver: true
      }),
      Animated.timing(progressAnim, {
        toValue: 1,
        duration: 1400,
        easing: Easing.inOut(Easing.quad),
        useNativeDriver: false // width-based progress
      }),
      Animated.timing(sweepAnim, {
        toValue: width * 0.8,
        duration: 1200,
        delay: 300,
        easing: Easing.inOut(Easing.cubic),
        useNativeDriver: true
      })
    ]).start();

    // 4. Warp-zoom transition directly into Bento dashboard at 1.8 seconds
    const exitTimer = setTimeout(() => {
      Animated.parallel([
        Animated.timing(containerScale, {
          toValue: 1.25,
          duration: 400,
          easing: Easing.in(Easing.cubic),
          useNativeDriver: true
        }),
        Animated.timing(containerOpacity, {
          toValue: 0,
          duration: 350,
          easing: Easing.linear,
          useNativeDriver: true
        })
      ]).start(() => {
        onFinish?.();
      });
    }, 1800);

    return () => clearTimeout(exitTimer);
  }, []);

  const progressWidth = progressAnim.interpolate({
    inputRange: [0, 1],
    outputRange: ['0%', '100%']
  });

  return (
    <Animated.View
      style={[
        styles.container,
        {
          transform: [{ scale: containerScale }],
          opacity: containerOpacity
        }
      ]}
    >
      <StatusBar barStyle="light-content" backgroundColor="#090A0F" />

      {/* Radar pulse rings */}
      <View style={styles.centerPulseContainer}>
        <Animated.View
          style={[
            styles.pulseRing,
            styles.pulseRingColor1,
            {
              transform: [{ scale: pulseRing1 }],
              opacity: ringOpacity1
            }
          ]}
        />
        <Animated.View
          style={[
            styles.pulseRing,
            styles.pulseRingColor2,
            {
              transform: [{ scale: pulseRing2 }],
              opacity: ringOpacity2
            }
          ]}
        />

        {/* Ambient neon backdrop glow */}
        <View style={styles.ambientGlow} />

        {/* Central Logo Emblem */}
        <Animated.View
          style={[
            styles.emblemContainer,
            {
              transform: [{ scale: logoScale }],
              opacity: logoOpacity
            }
          ]}
        >
          <View style={styles.emblemBorder}>
            <View style={styles.emblemInner}>
              <Text style={styles.emblemGlyph}>⚡</Text>
            </View>
          </View>
        </Animated.View>
      </View>

      {/* Typography with Anamorphic Light-Sweep */}
      <View style={styles.titleContainer}>
        <View style={styles.sweepWrapper}>
          <Text style={styles.titleText}>InstaPulse</Text>
          <Animated.View
            style={[
              styles.sweepBeam,
              {
                transform: [{ translateX: sweepAnim }]
              }
            ]}
          />
        </View>

        <Text style={styles.subtitleText}>RELATIONSHIP INTELLIGENCE ENGINE</Text>
      </View>

      {/* Live Loading Progress Bar */}
      <View style={styles.progressContainer}>
        <View style={styles.progressBarTrack}>
          <Animated.View style={[styles.progressBarFill, { width: progressWidth }]} />
        </View>
        <Text style={styles.loadingLabel}>Initializing O(1) Offline Engine...</Text>
      </View>
    </Animated.View>
  );
};

const styles = StyleSheet.create({
  container: {
    position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
    backgroundColor: '#090A0F',
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 9999
  },
  centerPulseContainer: {
    width: 200,
    height: 200,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 40
  },
  pulseRing: {
    position: 'absolute',
    width: 140,
    height: 140,
    borderRadius: 70,
    borderWidth: 2
  },
  pulseRingColor1: {
    borderColor: '#DD2A7B'
  },
  pulseRingColor2: {
    borderColor: '#8134AF'
  },
  ambientGlow: {
    position: 'absolute',
    width: 120,
    height: 120,
    borderRadius: 60,
    backgroundColor: '#DD2A7B',
    opacity: 0.25
  },
  emblemContainer: {
    width: 90,
    height: 90,
    alignItems: 'center',
    justifyContent: 'center'
  },
  emblemBorder: {
    width: 84,
    height: 84,
    borderRadius: 24,
    padding: 3,
    backgroundColor: '#DD2A7B'
  },
  emblemInner: {
    flex: 1,
    borderRadius: 21,
    backgroundColor: '#121520',
    alignItems: 'center',
    justifyContent: 'center'
  },
  emblemGlyph: {
    fontSize: 34,
    color: '#F8FAFC'
  },
  titleContainer: {
    alignItems: 'center',
    marginTop: 10
  },
  sweepWrapper: {
    position: 'relative',
    overflow: 'hidden',
    paddingHorizontal: 20,
    paddingVertical: 4
  },
  titleText: {
    fontSize: 34,
    fontWeight: '900',
    color: '#F8FAFC',
    letterSpacing: -0.5
  },
  sweepBeam: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    width: 60,
    backgroundColor: 'rgba(255, 255, 255, 0.45)',
    transform: [{ skewX: '-25deg' }]
  },
  subtitleText: {
    fontSize: 11,
    fontWeight: '700',
    color: '#94A3B8',
    letterSpacing: 2,
    marginTop: 6
  },
  progressContainer: {
    position: 'absolute',
    bottom: 60,
    width: width * 0.65,
    alignItems: 'center'
  },
  progressBarTrack: {
    width: '100%',
    height: 4,
    borderRadius: 2,
    backgroundColor: '#1E2335',
    overflow: 'hidden'
  },
  progressBarFill: {
    height: '100%',
    borderRadius: 2,
    backgroundColor: '#DD2A7B'
  },
  loadingLabel: {
    fontSize: 11,
    color: '#64748B',
    marginTop: 10,
    fontWeight: '500'
  }
});

export default CinematicSplash;



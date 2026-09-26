import React, { useState, useEffect, useRef } from 'react';
import { View, Text, StyleSheet, TouchableOpacity, ActivityIndicator, Animated } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { WebView } from 'react-native-webview';

export interface ActionQueueItem {
  pk?: string;
  username: string;
  action: 'UNFOLLOW' | 'FOLLOW';
}

interface InstaActionExecutorProps {
  visible: boolean;
  queue: ActionQueueItem[];
  mode: 'ASSIST' | 'AUTO_QUEUE';
  onClose: () => void;
  onActionStart: (pk: string) => void;
  onActionComplete: (item: ActionQueueItem, status?: 'SUCCESS' | 'SKIPPED_UNAVAILABLE' | 'ERROR') => void;
  onQueueFinished: () => void;
}

export const InstaActionExecutor: React.FC<InstaActionExecutorProps> = ({
  visible,
  queue,
  mode,
  onClose,
  onActionStart,
  onActionComplete,
  onQueueFinished
}) => {
  const [currentIndex, setCurrentIndex] = useState(0);
  const [isPaused, setIsPaused] = useState(false);
  const [countdown, setCountdown] = useState(0);
  const [statusMessage, setStatusMessage] = useState('Initializing Instagram session...');
  
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const watchdogRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const slideAnim = React.useMemo(() => new Animated.Value(100), []);

  const currentItem = queue[currentIndex] || null;
  const isBatch = queue.length > 1;

  const triggerNextWithHumanDelay = () => {
    const randomDelayMs = isBatch ? Math.floor(2500 + Math.random() * 2000) : 1000;
    setCountdown(Math.ceil(randomDelayMs / 1000));

    if (timerRef.current) clearTimeout(timerRef.current);
    
    let remaining = Math.ceil(randomDelayMs / 1000);
    const interval = setInterval(() => {
      remaining -= 1;
      if (remaining >= 0) setCountdown(remaining);
    }, 1000);

    timerRef.current = setTimeout(() => {
      clearInterval(interval);
      if (!isPaused) {
        if (currentIndex + 1 < queue.length) {
          setCurrentIndex(prev => prev + 1);
        } else {
          onQueueFinished();
          onClose();
        }
      }
    }, randomDelayMs);
  };

  const handleSkip = React.useCallback((item: ActionQueueItem) => {
    if (watchdogRef.current) clearTimeout(watchdogRef.current);
    setStatusMessage(`⚠️ Skipped unavailable account @${item.username} — continuing...`);
    onActionComplete(item, 'SKIPPED_UNAVAILABLE');
    triggerNextWithHumanDelay();
  }, [onActionComplete]);

  useEffect(() => {
    if (visible && currentItem) {
      Animated.spring(slideAnim, {
        toValue: 0,
        useNativeDriver: true,
        tension: 40,
        friction: 8
      }).start();
      
      onActionStart(currentItem.pk || '');
      
      if (watchdogRef.current) clearTimeout(watchdogRef.current);
      watchdogRef.current = setTimeout(() => {
        console.warn('[InstaPulse] Watchdog timeout triggered on @', currentItem.username);
        handleSkip(currentItem);
      }, 12000);
      
    } else {
      Animated.timing(slideAnim, {
        toValue: 150,
        useNativeDriver: true,
        duration: 300
      }).start();
    }
    
    return () => {
      if (watchdogRef.current) clearTimeout(watchdogRef.current);
    };
  }, [visible, currentIndex, currentItem, handleSkip, onActionStart, slideAnim]);

  const getInjectedScript = (item: ActionQueueItem, executionMode: 'ASSIST' | 'AUTO_QUEUE') => `
    (function() {
      const TARGET_ACTION = "${item.action}";
      const TARGET_USER = "${item.username}";
      const TARGET_PK = "${item.pk || ''}";
      
      const IG_APP_ID = '936619743392459';

      function post(data) {
        if (window.ReactNativeWebView && window.ReactNativeWebView.postMessage) {
          window.ReactNativeWebView.postMessage(JSON.stringify(data));
        }
      }

      function getCookie(name) {
        const match = document.cookie.match(new RegExp('(^| )' + name + '=([^;]+)'));
        return match ? decodeURIComponent(match[2]) : null;
      }
      
      async function executeApiAction() {
        try {
          const csrftoken = getCookie('csrftoken');
          let targetPk = TARGET_PK;
          
          if (!targetPk || !/^\\d+$/.test(targetPk)) {
            const resInfo = await fetch('https://www.instagram.com/api/v1/users/web_profile_info/?username=' + TARGET_USER, {
              headers: { 'x-ig-app-id': IG_APP_ID }
            });
            if (resInfo.ok) {
              const data = await resInfo.json();
              targetPk = data?.data?.user?.id;
            }
          }
          
          if (targetPk && /^\\d+$/.test(targetPk)) {
            const endpoint = TARGET_ACTION === 'UNFOLLOW' ? 'destroy' : 'create';
            const res = await fetch('https://www.instagram.com/api/v1/friendships/' + endpoint + '/' + targetPk + '/', {
              method: 'POST',
              headers: {
                'content-type': 'application/x-www-form-urlencoded',
                'x-csrftoken': csrftoken || '',
                'x-ig-app-id': IG_APP_ID,
                'x-requested-with': 'XMLHttpRequest'
              }
            });
            
            if (res.ok) {
              const json = await res.json();
              if (json.status === 'ok') {
                return true;
              }
            }
          }
        } catch (e) {
          console.error(e);
        }
        return false;
      }

      function findButtonByText(candidates, textPatterns) {
        for (let btn of candidates) {
          const text = (btn.innerText || btn.textContent || "").trim().toLowerCase();
          const aria = (btn.getAttribute("aria-label") || "").trim().toLowerCase();
          for (let pat of textPatterns) {
            if (text === pat || text.startsWith(pat) || aria.includes(pat)) {
              return btn;
            }
          }
        }
        return null;
      }
      
      // Step A
      function checkUnavailable() {
        const bodyText = document.body.innerText.toLowerCase();
        if (bodyText.includes("page isn't available") || bodyText.includes("link you followed may be broken")) {
          return true;
        }
        return false;
      }

      async function executeAction() {
        // Step A
        if (checkUnavailable()) {
          post({ type: 'ACTION_SKIPPED', reason: 'PAGE_UNAVAILABLE', username: TARGET_USER, status: 'SKIPPED_UNAVAILABLE' });
          return;
        }

        post({ type: 'STATUS', message: 'Executing via API...' });
        
        // Step B
        const apiSuccess = await executeApiAction();
        if (apiSuccess) {
          post({ type: 'ACTION_COMPLETE', action: TARGET_ACTION, username: TARGET_USER, status: 'SUCCESS' });
          return;
        }
        
        post({ type: 'STATUS', message: 'API failed, trying DOM fallback...' });
        
        // Step C
        const buttons = Array.from(document.querySelectorAll("button, div[role='button']"));
        if (TARGET_ACTION === 'UNFOLLOW') {
          const followingBtn = findButtonByText(buttons, ["following", "requested"]);
          if (followingBtn) {
            followingBtn.click();
            setTimeout(() => {
              const modalButtons = Array.from(document.querySelectorAll("button, div[role='button'], span"));
              const confirmUnfollowBtn = findButtonByText(modalButtons, ["unfollow"]);
              if (confirmUnfollowBtn) {
                confirmUnfollowBtn.click();
                setTimeout(() => {
                  post({ type: 'ACTION_COMPLETE', action: TARGET_ACTION, username: TARGET_USER, status: 'SUCCESS' });
                }, 1500);
              } else {
                post({ type: 'ACTION_ERROR', reason: 'Unfollow confirmation modal missing', username: TARGET_USER });
              }
            }, 1000);
          } else {
            post({ type: 'ACTION_ERROR', reason: 'Following button not found', username: TARGET_USER });
          }
        } else {
          // FOLLOW
          const followBtn = findButtonByText(buttons, ["follow back", "follow"]);
          if (followBtn) {
            followBtn.click();
            setTimeout(() => {
              post({ type: 'ACTION_COMPLETE', action: TARGET_ACTION, username: TARGET_USER, status: 'SUCCESS' });
            }, 1500);
          } else {
             post({ type: 'ACTION_ERROR', reason: 'Follow button not found', username: TARGET_USER });
          }
        }
      }

      if (document.readyState === "complete" || document.readyState === "interactive") {
        setTimeout(executeAction, 1800);
      } else {
        window.addEventListener("DOMContentLoaded", () => setTimeout(executeAction, 1800));
      }
    })();
    true;
  `;

  const handleWebViewMessage = (event: any) => {
    try {
      const data = JSON.parse(event.nativeEvent.data);
      if (watchdogRef.current) clearTimeout(watchdogRef.current); // Clear watchdog on any response
      
      if (data.type === 'ACTION_COMPLETE') {
        setStatusMessage(`✅ Successfully ${data.action.toLowerCase()}ed @${data.username}`);
        if (currentItem) onActionComplete(currentItem, 'SUCCESS');
        
        if (mode === 'AUTO_QUEUE' && !isPaused) {
          triggerNextWithHumanDelay();
        }
      } else if (data.type === 'ACTION_RATE_LIMITED') {
        setIsPaused(true);
        setStatusMessage("⚠️ Instagram server requested a breather — Queue paused");
      } else if (data.type === 'ACTION_ERROR') {
        setStatusMessage(`❌ Error: ${data.reason}`);
        if (currentItem) onActionComplete(currentItem, 'ERROR');
        // Do not pause queue, just continue to next after delay
        if (mode === 'AUTO_QUEUE' && !isPaused) triggerNextWithHumanDelay();
      } else if (data.type === 'ACTION_SKIPPED') {
        if (currentItem) handleSkip(currentItem);
      } else if (data.type === 'STATUS') {
        setStatusMessage(data.message);
      }
    } catch (e) {
      console.warn('Failed to parse webview message', e);
    }
  };

  useEffect(() => {
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
      if (watchdogRef.current) clearTimeout(watchdogRef.current);
    };
  }, []);

  if (!visible || !currentItem) return null;

  const currentUrl = `https://www.instagram.com/${currentItem.username}/`;

  return (
    <View style={StyleSheet.absoluteFill} pointerEvents="box-none">
      
      {/* 1. SILENT BACKGROUND EXECUTION */}
      <View style={{ position: 'absolute', top: -9999, left: -10, width: 375, height: 667, opacity: 0.02, pointerEvents: 'none', zIndex: -10 }}>
        <WebView
          source={{ uri: currentUrl }}
          injectedJavaScript={getInjectedScript(currentItem, mode)}
          onMessage={handleWebViewMessage}
          sharedCookiesEnabled={true}
          thirdPartyCookiesEnabled={true}
          domStorageEnabled={true}
          javaScriptEnabled={true}
          userAgent="Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
        />
      </View>

      {/* 4. COOL ANIMATED LOADING & BACKGROUND PROGRESS HUD */}
      <Animated.View style={[
        styles.hudContainer, 
        { transform: [{ translateY: slideAnim }] }
      ]}>
        <View style={styles.hudGlass}>
          <View style={styles.hudTopRow}>
            <View style={styles.hudStatusIndicator}>
              <ActivityIndicator size="small" color="#38BDF8" />
              <Text maxFontSizeMultiplier={1.15} style={styles.hudTitle}>
                {currentItem.action === 'UNFOLLOW' ? 'Unfollowing' : 'Following'} @{currentItem.username}
              </Text>
            </View>
            <View style={styles.hudBadge}>
              <Text maxFontSizeMultiplier={1.15} style={styles.hudBadgeText}>
                {currentIndex + 1} / {queue.length}
              </Text>
            </View>
          </View>
          
          <Text maxFontSizeMultiplier={1.15} style={styles.hudMessage} numberOfLines={1}>
            {statusMessage}
          </Text>
          
          {isPaused ? (
            <View style={[styles.cooldownContainer, { backgroundColor: 'rgba(255, 59, 92, 0.1)' }]}>
              <Text maxFontSizeMultiplier={1.15} style={[styles.cooldownText, { color: '#FF3B5C' }]}>
                Queue Paused
              </Text>
            </View>
          ) : countdown > 0 ? (
            <View style={styles.cooldownContainer}>
              <Text maxFontSizeMultiplier={1.15} style={styles.cooldownText}>
                Fast Pacing... wait {countdown}s
              </Text>
            </View>
          ) : null}

          {/* Animated Neon Shimmer Progress Bar */}
          <View style={styles.progressBarBg}>
             <View style={[styles.progressBarFill, { width: `${Math.max(5, ((currentIndex + 1) / queue.length) * 100)}%` }]} />
          </View>
        </View>
      </Animated.View>
      
    </View>
  );
};

const styles = StyleSheet.create({
  hudContainer: {
    position: 'absolute',
    bottom: 30,
    left: 20,
    right: 20,
    zIndex: 9999,
  },
  hudGlass: {
    backgroundColor: 'rgba(19, 23, 36, 0.95)',
    borderRadius: 20,
    padding: 16,
    borderWidth: 1.5,
    borderColor: 'rgba(255,255,255,0.1)',
    borderLeftWidth: 3,
    borderLeftColor: '#38BDF8',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 10 },
    shadowOpacity: 0.5,
    shadowRadius: 20,
    elevation: 10,
  },
  hudTopRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  hudStatusIndicator: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  hudTitle: {
    color: '#FFF',
    fontSize: 15,
    fontWeight: '700',
    marginLeft: 8,
  },
  hudBadge: {
    backgroundColor: 'rgba(56, 189, 248, 0.15)',
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 8,
  },
  hudBadgeText: {
    color: '#38BDF8',
    fontSize: 11,
    fontWeight: 'bold',
  },
  hudMessage: {
    color: '#94A3B8',
    fontSize: 13,
    marginBottom: 12,
  },
  cooldownContainer: {
    backgroundColor: 'rgba(245, 158, 11, 0.1)',
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 8,
    alignSelf: 'flex-start',
    marginBottom: 10,
  },
  cooldownText: {
    color: '#F59E0B',
    fontSize: 12,
    fontWeight: '600',
  },
  progressBarBg: {
    height: 4,
    backgroundColor: 'rgba(255,255,255,0.1)',
    borderRadius: 2,
    overflow: 'hidden',
  },
  progressBarFill: {
    height: '100%',
    backgroundColor: '#38BDF8',
    borderRadius: 2,
  }
});

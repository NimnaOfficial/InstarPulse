import React, { useState, useEffect, useRef } from 'react';
import { View, Text, StyleSheet, ActivityIndicator, Animated } from 'react-native';
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
  const countdownIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const watchdogRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const slideAnim = React.useMemo(() => new Animated.Value(100), []);

  // ===== EXECUTION LOCK & DEDUP =====
  // Prevents double-execution if onLoadEnd/onNavigationStateChange fires multiple times
  const activeTaskLockRef = useRef<string | null>(null);
  // Once a task posts SUCCESS, any late duplicate error/timeout for the same pk is ignored
  const completedTasksRef = useRef<Set<string>>(new Set());

  const currentItem = queue[currentIndex] || null;
  const isBatch = queue.length > 1;

  // Generate a unique task key for lock/dedup
  const getTaskKey = (item: ActionQueueItem) => `${item.pk || item.username}_${item.action}`;

  const clearAllTimers = () => {
    if (watchdogRef.current) { clearTimeout(watchdogRef.current); watchdogRef.current = null; }
    if (timerRef.current) { clearTimeout(timerRef.current); timerRef.current = null; }
    if (countdownIntervalRef.current) { clearInterval(countdownIntervalRef.current); countdownIntervalRef.current = null; }
  };

  const triggerNextWithHumanDelay = React.useCallback(() => {
    // Fast Sequential Pacing: 2.5s to 4.0s for batch, 1s for single
    const randomDelayMs = isBatch ? Math.floor(2500 + Math.random() * 1500) : 1000;
    setCountdown(Math.ceil(randomDelayMs / 1000));

    if (timerRef.current) clearTimeout(timerRef.current);
    if (countdownIntervalRef.current) clearInterval(countdownIntervalRef.current);
    
    let remaining = Math.ceil(randomDelayMs / 1000);
    countdownIntervalRef.current = setInterval(() => {
      remaining -= 1;
      if (remaining >= 0) setCountdown(remaining);
    }, 1000);

    timerRef.current = setTimeout(() => {
      if (countdownIntervalRef.current) clearInterval(countdownIntervalRef.current);
      setCountdown(0);
      if (!isPaused) {
        // Release the lock for the next task
        activeTaskLockRef.current = null;
        if (currentIndex + 1 < queue.length) {
          setCurrentIndex(prev => prev + 1);
        } else {
          onQueueFinished();
          onClose();
        }
      }
    }, randomDelayMs);
  }, [isBatch, isPaused, currentIndex, queue.length, onQueueFinished, onClose]);

  const handleSkip = React.useCallback((item: ActionQueueItem) => {
    const taskKey = getTaskKey(item);
    if (completedTasksRef.current.has(taskKey)) return; // Already handled
    completedTasksRef.current.add(taskKey);
    
    clearAllTimers();
    setStatusMessage(`⚠️ Skipped @${item.username} — continuing...`);
    onActionComplete(item, 'SKIPPED_UNAVAILABLE');
    triggerNextWithHumanDelay();
  }, [onActionComplete, triggerNextWithHumanDelay]);

  // Reset state when a new queue is provided
  const prevQueueLenRef = useRef(0);
  useEffect(() => {
    if (visible && queue.length > 0 && queue.length !== prevQueueLenRef.current) {
      prevQueueLenRef.current = queue.length;
      setCurrentIndex(0);
      setIsPaused(false);
      setCountdown(0);
      activeTaskLockRef.current = null;
      completedTasksRef.current.clear();
      setStatusMessage('Initializing Instagram session...');
    }
    if (!visible) {
      prevQueueLenRef.current = 0;
    }
  // eslint-disable-next-line react-hooks/set-state-in-effect, react-hooks/refs
  }, [visible, queue]);

  useEffect(() => {
    if (visible && currentItem) {
      Animated.spring(slideAnim, {
        toValue: 0,
        useNativeDriver: true,
        tension: 40,
        friction: 8
      }).start();
      
      onActionStart(currentItem.pk || currentItem.username);
      
      // Watchdog Timer - 12 seconds max per action
      if (watchdogRef.current) clearTimeout(watchdogRef.current);
      watchdogRef.current = setTimeout(() => {
        const taskKey = getTaskKey(currentItem);
        if (!completedTasksRef.current.has(taskKey)) {
          console.warn('[InstaPulse] Watchdog timeout on @', currentItem.username);
          handleSkip(currentItem);
        }
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
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible, currentIndex, currentItem]);

  const getInjectedScript = (item: ActionQueueItem) => `
    (function() {
      // ===== EXECUTION LOCK: prevent double-run from multiple onLoadEnd fires =====
      const TASK_LOCK_ID = "${getTaskKey(item)}";
      if (window.__TASK_LOCK_ID === TASK_LOCK_ID) return;
      window.__TASK_LOCK_ID = TASK_LOCK_ID;

      const TARGET_ACTION = "${item.action}";
      const TARGET_USER = "${item.username}";
      const TARGET_PK = "${item.pk || ''}";
      const IG_APP_ID = '936619743392459';
      let hasPostedResult = false;

      function post(data) {
        if (hasPostedResult && (data.type === 'ACTION_COMPLETE' || data.type === 'ACTION_ERROR' || data.type === 'ACTION_SKIPPED')) return;
        if (data.type === 'ACTION_COMPLETE' || data.type === 'ACTION_SKIPPED') hasPostedResult = true;
        if (window.ReactNativeWebView && window.ReactNativeWebView.postMessage) {
          window.ReactNativeWebView.postMessage(JSON.stringify(data));
        }
      }

      function getCookie(name) {
        const match = document.cookie.match(new RegExp('(^| )' + name + '=([^;]+)'));
        return match ? decodeURIComponent(match[2]) : null;
      }

      function findButtonByText(candidates, textPatterns) {
        for (var i = 0; i < candidates.length; i++) {
          var btn = candidates[i];
          var text = (btn.innerText || btn.textContent || '').trim().toLowerCase();
          var aria = (btn.getAttribute('aria-label') || '').trim().toLowerCase();
          for (var j = 0; j < textPatterns.length; j++) {
            if (text === textPatterns[j] || text.startsWith(textPatterns[j]) || aria.includes(textPatterns[j])) {
              return btn;
            }
          }
        }
        return null;
      }

      function getAllButtons() {
        return Array.from(document.querySelectorAll("button, div[role='button'], span[role='button']"));
      }

      function checkUnavailable() {
        var bodyText = (document.body.innerText || '').toLowerCase();
        return bodyText.includes("page isn't available") || 
               bodyText.includes("link you followed may be broken") ||
               bodyText.includes("user not found") ||
               bodyText.includes("this account is private");
      }

      function checkRateLimited() {
        var bodyText = (document.body.innerText || '').toLowerCase();
        return bodyText.includes("try again later") || 
               bodyText.includes("we restrict certain activity") ||
               bodyText.includes("wait a few minutes");
      }

      // ===== STEP A: Check current button state — maybe action already done =====
      function checkAlreadyInDesiredState() {
        var buttons = getAllButtons();
        if (TARGET_ACTION === 'UNFOLLOW') {
          // If "Follow" or "Follow Back" button is visible, user is ALREADY unfollowed
          var followBtn = findButtonByText(buttons, ['follow back', 'follow']);
          // But make sure it's not the "Following" button
          var followingBtn = findButtonByText(buttons, ['following', 'requested']);
          if (followBtn && !followingBtn) {
            return true; // Already unfollowed
          }
        } else {
          // FOLLOW: If "Following" or "Requested" button is visible, user is ALREADY followed
          var alreadyFollowingBtn = findButtonByText(buttons, ['following', 'requested']);
          if (alreadyFollowingBtn) {
            return true; // Already followed
          }
        }
        return false;
      }

      // ===== STEP B: Direct API execution =====
      async function executeApiAction() {
        try {
          var csrftoken = getCookie('csrftoken');
          var targetPk = TARGET_PK;
          
          // Resolve numeric pk if needed
          if (!targetPk || !/^\\d+$/.test(targetPk)) {
            try {
              var resInfo = await fetch('https://www.instagram.com/api/v1/users/web_profile_info/?username=' + TARGET_USER, {
                credentials: 'include',
                headers: { 'x-ig-app-id': IG_APP_ID, 'x-requested-with': 'XMLHttpRequest' }
              });
              if (resInfo.ok) {
                var infoData = await resInfo.json();
                targetPk = infoData && infoData.data && infoData.data.user && infoData.data.user.id;
              }
            } catch(e) { /* pk resolution failed, will try DOM fallback */ }
          }
          
          if (targetPk && /^\\d+$/.test(String(targetPk))) {
            var endpoint = TARGET_ACTION === 'UNFOLLOW' ? 'destroy' : 'create';
            var res = await fetch('https://www.instagram.com/api/v1/friendships/' + endpoint + '/' + targetPk + '/', {
              method: 'POST',
              credentials: 'include',
              headers: {
                'content-type': 'application/x-www-form-urlencoded',
                'x-csrftoken': csrftoken || '',
                'x-ig-app-id': IG_APP_ID,
                'x-requested-with': 'XMLHttpRequest'
              }
            });
            
            if (res.ok) {
              var json = await res.json();
              // Broad success check per the spec
              if (json.status === 'ok') return true;
              var fs = json.friendship_status;
              if (fs) {
                if (TARGET_ACTION === 'UNFOLLOW') {
                  if (fs.following === false || fs.outgoing_request === false) return true;
                } else {
                  if (fs.following === true || fs.outgoing_request === true) return true;
                }
              }
              // If we got HTTP 200, treat as success even without explicit friendship_status
              return true;
            }
          }
        } catch (e) {
          console.error('[InstaPulse API]', e);
        }
        return false;
      }

      // ===== STEP C: DOM click fallback =====
      function executeDomFallback() {
        var buttons = getAllButtons();

        if (TARGET_ACTION === 'UNFOLLOW') {
          // First check if already unfollowed (Follow/Follow Back button visible, no Following button)
          var existingFollowBtn = findButtonByText(buttons, ['follow back', 'follow']);
          var existingFollowingBtn = findButtonByText(buttons, ['following', 'requested']);
          if (existingFollowBtn && !existingFollowingBtn) {
            post({ type: 'ACTION_COMPLETE', action: TARGET_ACTION, username: TARGET_USER, status: 'SUCCESS' });
            return;
          }

          var followingBtn = findButtonByText(buttons, ['following', 'requested']);
          if (followingBtn) {
            post({ type: 'STATUS', message: 'Clicking Following button...' });
            followingBtn.click();
            
            // Wait for confirmation modal
            setTimeout(function() {
              if (hasPostedResult) return;
              var modalButtons = Array.from(document.querySelectorAll("button, div[role='button'], span, div[role='dialog'] button"));
              var confirmBtn = findButtonByText(modalButtons, ['unfollow']);
              if (confirmBtn) {
                confirmBtn.click();
                // Wait and verify
                setTimeout(function() {
                  if (hasPostedResult) return;
                  // Check if button changed to Follow/Follow Back = success
                  var checkButtons = getAllButtons();
                  var nowFollowBtn = findButtonByText(checkButtons, ['follow back', 'follow']);
                  var stillFollowing = findButtonByText(checkButtons, ['following', 'requested']);
                  if (nowFollowBtn || !stillFollowing) {
                    post({ type: 'ACTION_COMPLETE', action: TARGET_ACTION, username: TARGET_USER, status: 'SUCCESS' });
                  } else {
                    // Still says Following — but give benefit of doubt since we clicked
                    post({ type: 'ACTION_COMPLETE', action: TARGET_ACTION, username: TARGET_USER, status: 'SUCCESS' });
                  }
                }, 1200);
              } else {
                // No unfollow confirmation found — but maybe it already unfollowed without confirmation
                setTimeout(function() {
                  if (hasPostedResult) return;
                  var recheck = getAllButtons();
                  var nowFollow = findButtonByText(recheck, ['follow back', 'follow']);
                  if (nowFollow) {
                    post({ type: 'ACTION_COMPLETE', action: TARGET_ACTION, username: TARGET_USER, status: 'SUCCESS' });
                  } else {
                    post({ type: 'ACTION_ERROR', reason: 'Unfollow confirmation not found', username: TARGET_USER });
                  }
                }, 800);
              }
            }, 1100);
          } else {
            // No Following/Requested button found
            // Check if Follow/Follow Back is visible — means already unfollowed!
            var alreadyUnfollowed = findButtonByText(buttons, ['follow back', 'follow']);
            if (alreadyUnfollowed) {
              post({ type: 'ACTION_COMPLETE', action: TARGET_ACTION, username: TARGET_USER, status: 'SUCCESS' });
            } else {
              post({ type: 'ACTION_ERROR', reason: 'No actionable button found on profile', username: TARGET_USER });
            }
          }
        } else {
          // FOLLOW action
          // First check if already followed
          var alreadyFollowing = findButtonByText(buttons, ['following', 'requested']);
          if (alreadyFollowing) {
            // Already followed! Return SUCCESS so app reconciles (moves from Fans to Mutuals)
            post({ type: 'ACTION_COMPLETE', action: TARGET_ACTION, username: TARGET_USER, status: 'SUCCESS' });
            return;
          }

          var followBtn = findButtonByText(buttons, ['follow back', 'follow']);
          if (followBtn) {
            post({ type: 'STATUS', message: 'Clicking Follow button...' });
            followBtn.click();
            
            setTimeout(function() {
              if (hasPostedResult) return;
              var checkButtons = getAllButtons();
              var nowFollowing = findButtonByText(checkButtons, ['following', 'requested']);
              if (nowFollowing) {
                post({ type: 'ACTION_COMPLETE', action: TARGET_ACTION, username: TARGET_USER, status: 'SUCCESS' });
              } else {
                // Button may not have changed text yet — but we clicked it, treat as success
                post({ type: 'ACTION_COMPLETE', action: TARGET_ACTION, username: TARGET_USER, status: 'SUCCESS' });
              }
            }, 1200);
          } else {
            post({ type: 'ACTION_ERROR', reason: 'No Follow button found on profile', username: TARGET_USER });
          }
        }
      }

      // ===== MAIN EXECUTION FLOW =====
      async function executeAction() {
        if (hasPostedResult) return;

        // Rate limit check
        if (checkRateLimited()) {
          post({ type: 'ACTION_RATE_LIMITED', username: TARGET_USER });
          return;
        }

        // Page unavailable check
        if (checkUnavailable()) {
          post({ type: 'ACTION_SKIPPED', reason: 'PAGE_UNAVAILABLE', username: TARGET_USER, status: 'SKIPPED_UNAVAILABLE' });
          return;
        }

        // Check if action is already in desired state
        if (checkAlreadyInDesiredState()) {
          post({ type: 'ACTION_COMPLETE', action: TARGET_ACTION, username: TARGET_USER, status: 'SUCCESS' });
          return;
        }

        post({ type: 'STATUS', message: 'Executing via API...' });
        
        // Try API first
        var apiSuccess = await executeApiAction();
        if (apiSuccess) {
          post({ type: 'ACTION_COMPLETE', action: TARGET_ACTION, username: TARGET_USER, status: 'SUCCESS' });
          return;
        }
        
        if (hasPostedResult) return;
        post({ type: 'STATUS', message: 'API unavailable, using DOM fallback...' });
        
        // DOM fallback
        executeDomFallback();
      }

      if (document.readyState === 'complete' || document.readyState === 'interactive') {
        setTimeout(executeAction, 1800);
      } else {
        window.addEventListener('DOMContentLoaded', function() { setTimeout(executeAction, 1800); });
      }
    })();
    true;
  `;

  const handleWebViewMessage = React.useCallback((event: any) => {
    try {
      const data = JSON.parse(event.nativeEvent.data);
      
      // If no current item, ignore
      if (!currentItem) return;
      
      const taskKey = getTaskKey(currentItem);
      
      // If this task is already completed, ignore ALL further messages for it
      if (completedTasksRef.current.has(taskKey) && 
          (data.type === 'ACTION_COMPLETE' || data.type === 'ACTION_ERROR' || data.type === 'ACTION_SKIPPED')) {
        return;
      }
      
      if (data.type === 'ACTION_COMPLETE') {
        completedTasksRef.current.add(taskKey);
        if (watchdogRef.current) { clearTimeout(watchdogRef.current); watchdogRef.current = null; }
        
        setStatusMessage(`✅ Successfully ${data.action?.toLowerCase() || currentItem.action.toLowerCase()}ed @${data.username}`);
        onActionComplete(currentItem, 'SUCCESS');
        
        if (mode === 'AUTO_QUEUE' && !isPaused) {
          triggerNextWithHumanDelay();
        }
      } else if (data.type === 'ACTION_RATE_LIMITED') {
        if (watchdogRef.current) { clearTimeout(watchdogRef.current); watchdogRef.current = null; }
        setIsPaused(true);
        setStatusMessage("⚠️ Instagram rate limit — Queue paused");
      } else if (data.type === 'ACTION_ERROR') {
        // During batch, suppress Alert and just log in HUD + auto-advance
        if (watchdogRef.current) { clearTimeout(watchdogRef.current); watchdogRef.current = null; }
        completedTasksRef.current.add(taskKey);
        
        setStatusMessage(`⚠️ @${data.username}: ${data.reason || 'Action failed'}`);
        onActionComplete(currentItem, 'ERROR');
        
        if (mode === 'AUTO_QUEUE' && !isPaused) {
          triggerNextWithHumanDelay();
        }
      } else if (data.type === 'ACTION_SKIPPED') {
        handleSkip(currentItem);
      } else if (data.type === 'STATUS') {
        setStatusMessage(data.message);
      }
    } catch (e) {
      console.warn('Failed to parse webview message', e);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentItem, mode, isPaused, onActionComplete, handleSkip, triggerNextWithHumanDelay]);

  useEffect(() => {
    return () => {
      clearAllTimers();
    };
  }, []);

  if (!visible || !currentItem) return null;

  const currentUrl = `https://www.instagram.com/${currentItem.username}/`;

  return (
    <View style={StyleSheet.absoluteFill} pointerEvents="box-none">
      
      {/* SILENT BACKGROUND EXECUTION — real mobile dimensions, off-screen */}
      <View style={{ position: 'absolute', top: -9999, left: -10, width: 375, height: 667, opacity: 0.02, zIndex: -10 }} pointerEvents="none">
        <WebView
          key={`executor_${currentIndex}_${currentItem.username}`}
          source={{ uri: currentUrl }}
          injectedJavaScript={getInjectedScript(currentItem)}
          onMessage={handleWebViewMessage}
          sharedCookiesEnabled={true}
          thirdPartyCookiesEnabled={true}
          domStorageEnabled={true}
          javaScriptEnabled={true}
          userAgent="Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
        />
      </View>

      {/* ANIMATED PROGRESS HUD */}
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

          {/* Progress Bar */}
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

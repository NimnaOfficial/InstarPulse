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
  const [statusMessage, setStatusMessage] = useState('Initializing execution engine...');
  
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const countdownIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const watchdogRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const slideAnim = React.useMemo(() => new Animated.Value(100), []);
  const webViewRef = useRef<WebView>(null);

  // ===== EXECUTION LOCK & DEDUP =====
  const activeTaskLockRef = useRef<string | null>(null);
  const completedTasksRef = useRef<Set<string>>(new Set());

  const currentItem = queue[currentIndex] || null;
  const isBatch = queue.length > 1;

  const getTaskKey = (item: ActionQueueItem) => `${item.pk || item.username}_${item.action}`;

  const clearAllTimers = () => {
    if (watchdogRef.current) { clearTimeout(watchdogRef.current); watchdogRef.current = null; }
    if (timerRef.current) { clearTimeout(timerRef.current); timerRef.current = null; }
    if (countdownIntervalRef.current) { clearInterval(countdownIntervalRef.current); countdownIntervalRef.current = null; }
  };

  const triggerNextWithHumanDelay = React.useCallback(() => {
    // Ultra-Fast Sequential Pacing: 1.2s to 2.0s for batch, 0.8s for single
    const randomDelayMs = isBatch ? Math.floor(1200 + Math.random() * 800) : 800;
    setCountdown(Math.ceil(randomDelayMs / 1000));

    clearAllTimers();
    
    let remaining = Math.ceil(randomDelayMs / 1000);
    countdownIntervalRef.current = setInterval(() => {
      remaining -= 1;
      if (remaining >= 0) setCountdown(remaining);
    }, 1000);

    timerRef.current = setTimeout(() => {
      if (countdownIntervalRef.current) clearInterval(countdownIntervalRef.current);
      setCountdown(0);
      if (!isPaused) {
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
    if (completedTasksRef.current.has(taskKey)) return; 
    completedTasksRef.current.add(taskKey);
    
    clearAllTimers();
    setStatusMessage(`⚠️ Skipped @${item.username} — continuing...`);
    onActionComplete(item, 'SKIPPED_UNAVAILABLE');
    triggerNextWithHumanDelay();
  }, [onActionComplete, triggerNextWithHumanDelay]);

  const prevQueueLenRef = useRef(0);
  useEffect(() => {
    if (visible && queue.length > 0 && queue.length !== prevQueueLenRef.current) {
      prevQueueLenRef.current = queue.length;
      setCurrentIndex(0);
      setIsPaused(false);
      setCountdown(0);
      activeTaskLockRef.current = null;
      completedTasksRef.current.clear();
      setStatusMessage('Initializing execution engine...');
    }
    if (!visible) {
      prevQueueLenRef.current = 0;
    }
  // eslint-disable-next-line react-hooks/set-state-in-effect, react-hooks/refs
  }, [visible, queue]);

  const getExecuteScript = (item: ActionQueueItem) => `
    (async function() {
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

      async function executeApiAction() {
        if (hasPostedResult) return;
        post({ type: 'STATUS', message: 'Executing via API...' });

        try {
          var csrftoken = getCookie('csrftoken');
          var targetPk = TARGET_PK;
          
          if (!targetPk || !/^\\d+$/.test(targetPk)) {
            try {
              var resInfo = await fetch('https://www.instagram.com/api/v1/users/web_profile_info/?username=' + TARGET_USER, {
                credentials: 'include',
                headers: { 'x-ig-app-id': IG_APP_ID, 'x-requested-with': 'XMLHttpRequest' }
              });
              if (resInfo.status === 404) {
                 post({ type: 'ACTION_SKIPPED', reason: 'PAGE_UNAVAILABLE', username: TARGET_USER, status: 'SKIPPED_UNAVAILABLE' });
                 return;
              }
              if (resInfo.ok) {
                var infoData = await resInfo.json();
                targetPk = infoData && infoData.data && infoData.data.user && infoData.data.user.id;
              }
            } catch(e) { }
          }
          
          if (targetPk && /^\d+$/.test(String(targetPk))) {
            var endpoint = TARGET_ACTION === 'UNFOLLOW' ? 'destroy' : 'create';
            var formBody = 'container_module=profile&user_id=' + encodeURIComponent(targetPk);
            var wwwClaim = (typeof sessionStorage !== 'undefined' && sessionStorage.getItem('www-claim-v2')) || '0';

            var verifiedSuccess = false;

            // Tier 1: REST API with Form Body and full headers
            try {
              var res = await fetch('https://www.instagram.com/api/v1/friendships/' + endpoint + '/' + targetPk + '/', {
                method: 'POST',
                credentials: 'include',
                headers: {
                  'accept': '*/*',
                  'content-type': 'application/x-www-form-urlencoded',
                  'x-csrftoken': csrftoken || '',
                  'x-ig-app-id': IG_APP_ID,
                  'x-asbd-id': '129477',
                  'x-ig-www-claim': wwwClaim,
                  'x-instagram-ajax': '1012875432',
                  'x-requested-with': 'XMLHttpRequest',
                  'referer': 'https://www.instagram.com/' + TARGET_USER + '/'
                },
                body: formBody
              });
              
              if (res.status === 404) {
                 post({ type: 'ACTION_SKIPPED', reason: 'USER_NOT_FOUND', username: TARGET_USER, status: 'SKIPPED_UNAVAILABLE' });
                 return;
              }

              if (res.status === 429) {
                 post({ type: 'ACTION_RATE_LIMITED', username: TARGET_USER });
                 return;
              }

              if (res.ok) {
                var json = await res.json();
                if (json && (json.status === 'ok' || json.friendship_status)) {
                  verifiedSuccess = true;
                }
              }
            } catch(e1) {}

            // Tier 2: GraphQL / Polaris Mutation Fallback
            if (!verifiedSuccess) {
              try {
                var gqlDocId = TARGET_ACTION === 'UNFOLLOW' ? '7301295479961089' : '7258991244196452';
                var friendlyName = TARGET_ACTION === 'UNFOLLOW' ? 'usePolarisUnfollowMutation' : 'usePolarisFollowMutation';
                var gqlBody = 'av=' + encodeURIComponent(getCookie('ds_user_id') || '') +
                  '&fb_api_req_friendly_name=' + friendlyName +
                  '&variables=' + encodeURIComponent(JSON.stringify({ target_user_id: targetPk, container_module: 'profile' })) +
                  '&doc_id=' + gqlDocId;

                var gqlRes = await fetch('https://www.instagram.com/graphql/query', {
                  method: 'POST',
                  credentials: 'include',
                  headers: {
                    'content-type': 'application/x-www-form-urlencoded',
                    'x-ig-app-id': IG_APP_ID,
                    'x-asbd-id': '129477',
                    'x-csrftoken': csrftoken || '',
                    'x-fb-friendly-name': friendlyName
                  },
                  body: gqlBody
                });
                if (gqlRes.ok) verifiedSuccess = true;
              } catch(e2) {}
            }

            // Tier 3: Real-Time Server Truth Verification
            try {
              var verifyRes = await fetch('https://www.instagram.com/api/v1/friendships/show/' + targetPk + '/', {
                method: 'GET',
                credentials: 'include',
                headers: {
                  'x-ig-app-id': IG_APP_ID,
                  'x-asbd-id': '129477',
                  'x-csrftoken': csrftoken || '',
                  'x-requested-with': 'XMLHttpRequest'
                }
              });

              if (verifyRes.ok) {
                var fs = await verifyRes.json();
                if (TARGET_ACTION === 'UNFOLLOW' && (fs.following === false && fs.outgoing_request === false)) {
                  verifiedSuccess = true;
                } else if (TARGET_ACTION === 'FOLLOW' && (fs.following === true || fs.outgoing_request === true)) {
                  verifiedSuccess = true;
                }
              }
            } catch(e3) {}

            if (verifiedSuccess) {
              post({ type: 'ACTION_COMPLETE', action: TARGET_ACTION, username: TARGET_USER, status: 'SUCCESS' });
              return;
            }
          }
          
          if (!hasPostedResult) {
            post({ type: 'ACTION_ERROR', reason: 'Action verification failed', username: TARGET_USER });
          }
        } catch (e) {
          if (!hasPostedResult) {
            post({ type: 'ACTION_ERROR', reason: 'Network error', username: TARGET_USER });
          }
        }
      }

      await executeApiAction();
    })();
    true;
  `;

  useEffect(() => {
    if (visible && currentItem) {
      Animated.spring(slideAnim, {
        toValue: 0,
        useNativeDriver: true,
        tension: 40,
        friction: 8
      }).start();
      
      onActionStart(currentItem.pk || currentItem.username);
      
      // Inject script dynamically without reloading WebView
      setTimeout(() => {
        if (webViewRef.current) {
          webViewRef.current.injectJavaScript(getExecuteScript(currentItem));
        }
      }, 100);
      
      // Watchdog Timer - fast 6 seconds max since it's pure API
      if (watchdogRef.current) clearTimeout(watchdogRef.current);
      watchdogRef.current = setTimeout(() => {
        const taskKey = getTaskKey(currentItem);
        if (!completedTasksRef.current.has(taskKey)) {
          console.warn('[InstaPulse] Watchdog timeout on @', currentItem.username);
          handleSkip(currentItem);
        }
      }, 6000);
      
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

  const handleWebViewMessage = React.useCallback((event: any) => {
    try {
      const data = JSON.parse(event.nativeEvent.data);
      if (!currentItem) return;
      
      const taskKey = getTaskKey(currentItem);
      
      if (completedTasksRef.current.has(taskKey) && 
          (data.type === 'ACTION_COMPLETE' || data.type === 'ACTION_ERROR' || data.type === 'ACTION_SKIPPED')) {
        return;
      }
      
      if (data.type === 'ACTION_COMPLETE') {
        completedTasksRef.current.add(taskKey);
        if (watchdogRef.current) { clearTimeout(watchdogRef.current); watchdogRef.current = null; }
        
        setStatusMessage(`✅ Successfully ${data.action?.toLowerCase() || currentItem.action.toLowerCase()}ed @${data.username}`);
        onActionComplete(currentItem, 'SUCCESS');
        
        if (mode === 'AUTO_QUEUE' && !isPaused) triggerNextWithHumanDelay();
      } else if (data.type === 'ACTION_RATE_LIMITED') {
        if (watchdogRef.current) { clearTimeout(watchdogRef.current); watchdogRef.current = null; }
        setIsPaused(true);
        setStatusMessage("⚠️ Instagram rate limit — Queue paused");
      } else if (data.type === 'ACTION_ERROR') {
        if (watchdogRef.current) { clearTimeout(watchdogRef.current); watchdogRef.current = null; }
        completedTasksRef.current.add(taskKey);
        setStatusMessage(`⚠️ @${data.username}: ${data.reason || 'Action failed'}`);
        onActionComplete(currentItem, 'ERROR');
        
        if (mode === 'AUTO_QUEUE' && !isPaused) triggerNextWithHumanDelay();
      } else if (data.type === 'ACTION_SKIPPED') {
        handleSkip(currentItem);
      } else if (data.type === 'STATUS') {
        setStatusMessage(data.message);
      }
    } catch (e) {}
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentItem, mode, isPaused, onActionComplete, handleSkip, triggerNextWithHumanDelay]);

  useEffect(() => {
    return () => {
      clearAllTimers();
    };
  }, []);

  if (!visible || !currentItem) return null;

  return (
    <View style={StyleSheet.absoluteFill} pointerEvents="box-none">
      
      {/* 
        SILENT IN-MEMORY EXECUTION 
        Parked on IG root. We use injectJavaScript to fire actions instantly without navigation overhead.
      */}
      <View style={{ position: 'absolute', top: -9999, left: -10, width: 375, height: 667, opacity: 0.02, zIndex: -10 }} pointerEvents="none">
        <WebView
          ref={webViewRef}
          source={{ uri: 'https://www.instagram.com/' }}
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
                Ultra-Fast Pacing... wait {countdown}s
              </Text>
            </View>
          ) : null}

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

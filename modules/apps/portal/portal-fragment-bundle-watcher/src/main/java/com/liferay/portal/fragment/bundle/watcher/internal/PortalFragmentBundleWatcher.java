/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.portal.fragment.bundle.watcher.internal;

import com.liferay.petra.string.CharPool;
import com.liferay.petra.string.StringPool;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.module.framework.ModuleServiceLifecycle;
import com.liferay.portal.kernel.util.StringUtil;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.Constants;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.util.tracker.BundleTracker;

/**
 * @author Shuyang Zhou
 */
@Component(service = {})
public class PortalFragmentBundleWatcher {

	@Activate
	protected void activate(BundleContext bundleContext) {
		_bundleContext = bundleContext;

		_installedFragmentBundleTracker = new BundleTracker<String>(
			_bundleContext, Bundle.INSTALLED, null) {

			@Override
			public String addingBundle(Bundle bundle, BundleEvent event) {
				if (!_isFragment(bundle)) {
					return null;
				}

				return _getFragmentHost(bundle);
			}

		};

		_installedFragmentBundleTracker.open();

		Bundle systemBundle = _bundleContext.getBundle(0);

		_frameworkWiring = systemBundle.adapt(FrameworkWiring.class);

		_resolvedBundleListener = bundleEvent -> {
			Bundle bundleEventBundle = bundleEvent.getBundle();

			if (((bundleEvent.getType() == BundleEvent.INSTALLED) &&
				(bundleEventBundle.getState() != Bundle.UNINSTALLED) &&
				_isFragment(bundleEventBundle)) ||
				(bundleEvent.getType() == BundleEvent.RESOLVED)) {

				Map<Bundle, String> installedFragmentBundles =
					_installedFragmentBundleTracker.getTracked();

				if (installedFragmentBundles.isEmpty()) {
					return;
				}

				Map<String, List<Bundle>> fragmentBundlesMap = new HashMap<>();

				for (Map.Entry<Bundle, String> entry :
						installedFragmentBundles.entrySet()) {

					List<Bundle> fragmentBundles =
						fragmentBundlesMap.computeIfAbsent(
							entry.getValue(), key -> new ArrayList<>());

					fragmentBundles.add(entry.getKey());
				}

				Bundle originBundle = bundleEvent.getOrigin();

				long originBundleId = originBundle.getBundleId();

				List<Bundle> hostBundles = new ArrayList<>();

				for (Bundle bundle : bundleContext.getBundles()) {
					List<Bundle> fragmantBundles = fragmentBundlesMap.remove(
						bundle.getSymbolicName());

					if (fragmantBundles == null) {
						continue;
					}

					if (originBundleId != bundle.getBundleId()) {
						boolean needRefresh = false;

						for (Bundle fragmentBundle : fragmantBundles) {
							if (fragmentBundle.getState() == Bundle.INSTALLED) {
								needRefresh = true;

								break;
							}
						}

						if (needRefresh) {
							hostBundles.add(bundle);
						}
					}

					if (fragmentBundlesMap.isEmpty()) {
						break;
					}
				}

				if (_log.isDebugEnabled()) {
					StringBuilder sb = new StringBuilder();

					sb.append(bundleEventBundle.toString());

					if (!hostBundles.isEmpty()) {
						sb.append(" refresh ");

						for (Bundle bundle : hostBundles) {
							sb.append(bundle.getSymbolicName()).append(" ");
						}
					}
					else {
						sb.append(" no refresh ");
					}

					_log.debug(sb.toString());
				}

				if (!hostBundles.isEmpty()) {
					//frameworkWiring.refreshBundles(hostBundles);
					_updateBuffer(hostBundles);
				}
			}
		};

		_bundleContext.addBundleListener(_resolvedBundleListener);
	}

	@Deactivate
	protected void deactivate() {
		_bundleContext.removeBundleListener(_resolvedBundleListener);

		_installedFragmentBundleTracker.close();

		if (_timer != null) {
			_timer.cancel();

			_timer = null;
		}

		_refreshBundles(true);
	}

	private String _getFragmentHost(Bundle bundle) {
		Dictionary<String, String> dictionary = bundle.getHeaders(
			StringPool.BLANK);

		String fragmentHost = dictionary.get(Constants.FRAGMENT_HOST);

		if (fragmentHost == null) {
			return null;
		}

		int index = fragmentHost.indexOf(CharPool.SEMICOLON);

		if (index != -1) {
			fragmentHost = fragmentHost.substring(0, index);
		}

		return fragmentHost;
	}

	/**
	 * @see com.liferay.portal.file.install.internal.DirectoryWatcher#_isFragment
	 */
	private boolean _isFragment(Bundle bundle) {
		BundleRevision bundleRevision = bundle.adapt(BundleRevision.class);

		if ((bundleRevision.getTypes() & BundleRevision.TYPE_FRAGMENT) != 0) {
			return true;
		}

		return false;
	}

	private void _refreshBundles(boolean force) {
		long now = System.currentTimeMillis();

		System.out.println("fire " + now);

		if (_hostBuffer.isEmpty() && _timer != null) {
			_timer.cancel();

			_timer = null;
		}

		Iterator<Long> iterator = _hostBuffer.keySet().iterator();

		while (iterator.hasNext()) {
			long time = iterator.next();

			if (force || (now - time > (_DELAY * 2))) {
				Set<Bundle> bundles = _hostBuffer.remove(time);

				System.out.println("fire " + time + " " + StringUtil.merge(bundles));

				if (bundles != null && !bundles.isEmpty()) {
					_frameworkWiring.refreshBundles(bundles);
				}
			}
		}
	}

	private void _updateBuffer(List<Bundle> hostBundles) {
		long now = System.currentTimeMillis();

		System.out.println("updateBuffer " + now + " " + StringUtil.merge(hostBundles));

		Set<Bundle> buffer = null;

		for (long time : _hostBuffer.keySet()) {
			if (now - time < _DELAY) {
				buffer = _hostBuffer.get(time);

				System.out.println("reuse " + time);

				break;
			}
		}

		if (buffer == null) {
			System.out.println("create " + now);

			buffer = new HashSet<Bundle>();

			_hostBuffer.put(now, buffer);

			TimerTask timerTask = new TimerTask() {

				@Override
				public void run() {
					_refreshBundles(false);
				}

			};

			_timer = new Timer(true);

			_timer.schedule(timerTask, _DELAY, _DELAY);
		}

		buffer.addAll(hostBundles);
	}

	private static final Log _log = LogFactoryUtil.getLog(
		PortalFragmentBundleWatcher.class);

	private static final long _DELAY = 5000L;

	private BundleContext _bundleContext;
	private FrameworkWiring _frameworkWiring;
	private Map<Long, Set<Bundle>> _hostBuffer = new ConcurrentHashMap<>();
	private BundleTracker<String> _installedFragmentBundleTracker;

	@Reference(target = ModuleServiceLifecycle.PORTAL_INITIALIZED)
	private ModuleServiceLifecycle _moduleServiceLifecycle;

	private BundleListener _resolvedBundleListener;
	private Timer _timer;

}
package net.jjc1138.android.twitter;

import static org.apache.commons.lang.StringEscapeUtils.unescapeHtml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;

import javax.xml.parsers.FactoryConfigurationError;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.widget.RemoteViews;

public class Fetcher extends Service {
	final static String LOG_TAG = "nanoTweeter";

	final static String LAST_TWEET_ID_FILENAME = "lasttweets";

	final static int FILTER_NONE = 0;
	final static int FILTER_WHITELIST = 1;
	final static int FILTER_BLACKLIST = 2;

	private SharedPreferences prefs;

	private FetcherThread fetcherThread;
	private Handler handler;
	private PowerManager.WakeLock wakeLock;

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		
		prefs = getSharedPreferences(TwitterConfig.PREFS, 0);
		handler = new Handler();
		wakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
			.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOG_TAG);
		wakeLock.setReferenceCounted(false);
	}

	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		
		wakeLock.acquire();
		Log.d(LOG_TAG, "Service started.");
		
		if (!prefs.getBoolean("enable", false)) {
			Log.d(LOG_TAG, "Fetching disabled.");
			stopIfIdle();
			return;
		}
		
		((AlarmManager) getSystemService(ALARM_SERVICE)).set(
			AlarmManager.ELAPSED_REALTIME_WAKEUP,
			SystemClock.elapsedRealtime() +
				(prefs.getInt("interval",
					TwitterConfig.INTERVALS[
						TwitterConfig.DEFAULT_INTERVAL_INDEX]) * 60 * 1000),
			PendingIntent.getService(this, 0,
				new Intent(this, Fetcher.class), 0));
		Log.d(LOG_TAG, "Scheduled next run.");
		
		if (fetcherThread != null && fetcherThread.inProgress()) {
			Log.d(LOG_TAG, "Refusing to start another fetcher " +
				"because one is in progress.");
			return;
		} else {
			fetcherThread = new FetcherThread();
			fetcherThread.start();
		}
	}

	private class FetcherThread extends Thread {
		private final Charset FILE_CHARSET = Charset.forName("US-ASCII");
		private static final int ERROR_NOTIFICATION_ID = 0;
	
		private static final String API_HOST = "twitter.com";
		private static final int API_PORT = 443;
		private static final String API_ROOT =
			"https://" + API_HOST + ":" + API_PORT + "/";
	
		private static final int FIRST_RUN_NOTIFICATIONS = 3;
		// This should be 200, but firing so many notifications causes a memory
		// exhaustion that crashes the phone:
		private static final int MAX_NOTIFICATIONS = 100;
	
		// stopIfIdle() below needs to know if this thread is still doing work.
		// You might think that we could use Thread.isAlive() to find that out,
		// but that wouldn't work because stopIfIdle() is queued to be called
		// from this thread, and it would be possible for stopIfIdle() to start
		// before this thread had actually died. For that reason we use this
		// variable so that the thread can indicate its impending death before
		// queuing the call to stopIfIdle().
		private volatile boolean inProgress = true;
	
		public boolean inProgress() {
			return inProgress;
		}
	
		private void finish(HttpEntity ent) {
			if (ent != null) {
				try {
					ent.consumeContent();
				} catch (IOException e) {
				}
			}
		}
	
		private class DownloadException extends Exception {
			private static final long serialVersionUID = 1L;
		}
	
		private HttpEntity download(DefaultHttpClient client, URI uri)
			throws DownloadException {
			
			HttpResponse r = null;
			try {
				r = client.execute(new HttpGet(uri));
			} catch (ClientProtocolException e) {
				assert false;
				throw new DownloadException();
			} catch (IOException e) {
				throw new DownloadException();
			}
			
			int status = r.getStatusLine().getStatusCode();
			HttpEntity ent = r.getEntity();
			
			NotificationManager nm =
				(NotificationManager) getSystemService(NOTIFICATION_SERVICE);
			
			if (status == HttpStatus.SC_UNAUTHORIZED) {
				Notification n = new Notification();
				n.icon = R.drawable.icon; // TODO proper error icon
				Intent i = new Intent(
					Fetcher.this, TwitterConfig.class);
				i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				n.setLatestEventInfo(Fetcher.this,
					getString(R.string.app_name),
					getString(R.string.unauthorized),
					PendingIntent.getActivity(Fetcher.this, 0, i, 0));
				n.flags |= Notification.FLAG_AUTO_CANCEL;
				nm.notify(ERROR_NOTIFICATION_ID, n);
				
				finish(ent);
				throw new DownloadException();
			} else {
				nm.cancel(ERROR_NOTIFICATION_ID);
			}
			
			if (status == HttpStatus.SC_OK) {
				// Coolness.
				return ent;
			} else if (status == HttpStatus.SC_NOT_MODIFIED) {
				// Nothing new.
				finish(ent);
				return null;
			} else {
				// All other response codes are essentially transient errors.
				// "403 Forbidden" and "404 Not Found" are exceptions, but there
				// isn't anything reasonable we can do to recover from them.
				finish(ent);
				throw new DownloadException();
			}
		}
	
		private String newLinesToSpaces(String s) {
			return s.replace('\n', ' ');
		}
	
		// You might think that lastFriendStatus and lastReply could be merged
		// into one variable, but that would cause a race because when we're
		// checking the replies a new friend status could be posted with an ID
		// less than the newest reply. That status would then not be fetched on
		// the next update.
		long lastFriendStatus = 1;
		long lastMessage = 1;
		long lastReply = 1;
	
		public void fetch() throws DownloadException {
			final String username = prefs.getString("username", "");
			final String password = prefs.getString("password", "");
			if (username.length() == 0 || password.length() == 0) {
				Log.d(LOG_TAG,
					"Skipping fetch because we have no credentials.");
				return;
			}
			
			{
				BufferedReader br = null;
				try {
					br = new BufferedReader(new InputStreamReader(
						openFileInput(LAST_TWEET_ID_FILENAME), FILE_CHARSET),
						32);
					lastFriendStatus = Long.parseLong(br.readLine());
					lastMessage = Long.parseLong(br.readLine());
					lastReply = Long.parseLong(br.readLine());
				} catch (IOException e) {
				} catch (NumberFormatException e) {
				} finally {
					if (br != null) {
						try {
							br.close();
						} catch (IOException e) {
						}
					}
				}
			}
			
			abstract class PathHandler extends DefaultHandler {
				private ArrayList<String> path =
					new ArrayList<String>();
				private ArrayList<StringBuffer> text =
					new ArrayList<StringBuffer>();
			
				protected boolean pathEquals(String[] a) {
					int size = a.length;
					if (path.size() != size) {
						return false;
					}
					for (int i = 0; i < size; ++i) {
						if (!a[i].equals(path.get(i))) {
							return false;
						}
					}
					return true;
				}
			
				protected String getCurrentText() {
					int depth = text.size();
					if (depth == 0) {
						return "";
					}
					return text.get(depth - 1).toString();
				}
			
				@Override
				public void characters(char[] ch, int start, int length)
					throws SAXException {
					
					int depth = text.size();
					if (depth == 0) {
						return;
					}
					text.get(depth - 1).append(ch, start, length);
				}
			
				@Override
				public void startElement(String uri, String localName,
					String name, Attributes attributes)
					throws SAXException {
					
					path.add(localName);
					text.add(new StringBuffer(0));
				}
			
				abstract void endElement();
			
				@Override
				public void endElement(String uri, String localName,
					String name) throws SAXException {
					
					endElement();
					int last = path.size() - 1;
					path.remove(last);
					text.remove(last);
				}
			}
			
			abstract class Tweet {
				public Tweet(
					long id, Date date, String screenName, String text) {
					
					this.id = id;
					this.date = date;
					this.screenName = screenName;
					this.text = text;
				}
			
				public long getID() {
					return id;
				}
				public Date getDate() {
					return date;
				}
				public String getScreenName() {
					return screenName;
				}
				public String getText() {
					return text;
				}
			
				abstract public String toString();
			
				private long id;
				private Date date;
				private String screenName;
				private String text;
			}
			
			class Status extends Tweet {
				public Status(
					long id, Date date, String screenName, String text) {
					
					super(id, date, screenName, text);
				}
			
				public String toString() {
					return "[" + getScreenName() + "]: " + getText();
				}
			}
			class Message extends Tweet {
				public Message(
					long id, Date date, String screenName, String text) {
					
					super(id, date, screenName, text);
				}
			
				public String toString() {
					return "M[" + getScreenName() + "]: " + getText();
				}
			}
			
			final DateFormat twitterDateFormat = new SimpleDateFormat(
				"E MMM dd HH:mm:ss Z yyyy");
			final LinkedList<Tweet> tweets = new LinkedList<Tweet>();
			abstract class StatusHandler extends PathHandler {
				private final String[] statusPath = {
					"statuses", "status" };
				private final String[] createdAtPath = {
					"statuses", "status", "created_at" };
				private final String[] idPath = {
					"statuses", "status", "id" };
				private final String[] textPath = {
					"statuses", "status", "text" };
				private final String[] screenNamePath = {
					"statuses", "status", "user", "screen_name" };
			
				private Date createdAt;
				private long id;
				private String text;
				private String screenName;
			
				abstract void updateLast(long id);
			
				@Override
				void endElement() {
					if (pathEquals(statusPath)) {
						if (!screenName.equals(username)) {
							Status s = new Status(
								id, createdAt, screenName, text);
							tweets.addFirst(s);
							// FIXME remove debug (privacy):
							Log.v(LOG_TAG, s.toString());
						}
					} else if (pathEquals(createdAtPath)) {
						try {
							createdAt = twitterDateFormat.parse(
								unescapeHtml(getCurrentText()));
						} catch (ParseException e) {
							createdAt = new Date(System.currentTimeMillis());
						}
					} else if (pathEquals(idPath)) {
						try {
							id = Long.parseLong(getCurrentText());
						} catch (NumberFormatException e) {
						}
						updateLast(id);
					} else if (pathEquals(textPath)) {
						text = newLinesToSpaces(unescapeHtml(getCurrentText()));
					} else if (pathEquals(screenNamePath)) {
						screenName = unescapeHtml(getCurrentText());
					}
				}
			}
			
			class FriendStatusHandler extends StatusHandler {
				@Override
				void updateLast(long id) {
					lastFriendStatus = Math.max(lastFriendStatus, id);
				}
			}
			class ReplyHandler extends StatusHandler {
				@Override
				void updateLast(long id) {
					lastReply = Math.max(lastReply, id);
				}
			}
			
			class MessageHandler extends PathHandler {
				private final String[] messagePath = {
					"direct-messages", "direct_message" };
				private final String[] createdAtPath = {
					"direct-messages", "direct_message", "created_at" };
				private final String[] idPath = {
					"direct-messages", "direct_message", "id" };
				private final String[] textPath = {
					"direct-messages", "direct_message", "text" };
				private final String[] screenNamePath = {
					"direct-messages", "direct_message",
					"sender", "screen_name" };
			
				private Date createdAt;
				private long id;
				private String text;
				private String screenName;
			
				@Override
				void endElement() {
					if (pathEquals(messagePath)) {
						Message m = new Message(
							id, createdAt, screenName, text);
						tweets.addFirst(m);
						// FIXME remove debug (privacy):
						Log.v(LOG_TAG, m.toString());
					} else if (pathEquals(createdAtPath)) {
						try {
							createdAt = twitterDateFormat.parse(
								unescapeHtml(getCurrentText()));
						} catch (ParseException e) {
							createdAt = new Date(System.currentTimeMillis());
						}
					} else if (pathEquals(idPath)) {
						try {
							id = Long.parseLong(getCurrentText());
						} catch (NumberFormatException e) {
						}
						lastMessage = Math.max(lastMessage, id);
					} else if (pathEquals(textPath)) {
						text = newLinesToSpaces(unescapeHtml(getCurrentText()));
					} else if (pathEquals(screenNamePath)) {
						screenName = unescapeHtml(getCurrentText());
					}
				}
			}
			
			XMLReader reader;
			try {
				reader = SAXParserFactory.newInstance()
					.newSAXParser().getXMLReader();
			} catch (SAXException e) {
				assert false;
				throw new DownloadException();
			} catch (ParserConfigurationException e) {
				assert false;
				throw new DownloadException();
			} catch (FactoryConfigurationError e) {
				assert false;
				throw new DownloadException();
			}
			InputSource is = new InputSource();
			is.setEncoding("UTF-8");
			
			DefaultHttpClient client = new DefaultHttpClient();
			client.getCredentialsProvider().setCredentials(
				new AuthScope(API_HOST, API_PORT),
				new UsernamePasswordCredentials(username, password));
			
			final boolean firstRun = (lastFriendStatus == 1);
			final int filterType = prefs.getInt(
				"filter_type", FILTER_NONE);
			
			HttpEntity ent = null;
			try {
				ent = download(client, new URI(API_ROOT +
					"statuses/friends_timeline.xml" + "?" +
					(firstRun ? "" : ("since_id=" + lastFriendStatus + "&")) +
					"count=" + ((firstRun && filterType == FILTER_NONE) ?
						FIRST_RUN_NOTIFICATIONS : MAX_NOTIFICATIONS)));
				if (ent != null) {
					reader.setContentHandler(new FriendStatusHandler());
					is.setByteStream(ent.getContent());
					reader.parse(is);
					
					if (filterType != FILTER_NONE) {
						String[] filterNames =
							prefs.getString("filter", "").split(" ");
						// Sort so that we can use binary search in a moment:
						Arrays.sort(filterNames);
						
						for (Iterator<Tweet> i = tweets.iterator();
							i.hasNext();) {
							
							final String screenName = i.next().getScreenName();
							final boolean filtered = Arrays.binarySearch(
								filterNames, screenName) >= 0;
							if (filterType == FILTER_WHITELIST) {
								if (!filtered) {
									i.remove();
								}
							} else {
								assert filterType == FILTER_BLACKLIST;
								if (filtered) {
									i.remove();
								}
							}
						}
					}
				}
				
				if (prefs.getBoolean("messages", false)) {
					ent = download(client, new URI(API_ROOT +
						"direct_messages.xml" + "?" +
						"since_id=" + lastMessage));
					if (ent != null) {
						reader.setContentHandler(new MessageHandler());
						is.setByteStream(ent.getContent());
						reader.parse(is);
					}
				}
				
				if (prefs.getBoolean("replies", false)) {
					ent = download(client, new URI(API_ROOT +
						"statuses/replies.xml" + "?" +
						"since_id=" + lastReply));
					if (ent != null) {
						reader.setContentHandler(new ReplyHandler());
						is.setByteStream(ent.getContent());
						reader.parse(is);
					}
				}
			} catch (SAXException e) {
				throw new DownloadException();
			} catch (IOException e) {
				throw new DownloadException();
			} catch (URISyntaxException e) {
				assert false;
				throw new DownloadException();
			} finally {
				finish(ent);
			}
			
			Collections.sort(tweets, new Comparator<Tweet>() {
				@Override
				public int compare(Tweet t1, Tweet t2) {
					return t1.getDate().compareTo(t2.getDate());
				}
			});
			
			final int limit =
				firstRun ? FIRST_RUN_NOTIFICATIONS : MAX_NOTIFICATIONS;
			for (int nTweets = tweets.size(); nTweets > limit; --nTweets) {
				tweets.removeFirst();
			}
			
			final String twitterRoot = "http://m.twitter.com/";
			final DateFormat df = DateFormat.getTimeInstance(DateFormat.SHORT);
			final NotificationManager nm =
				(NotificationManager) getSystemService(NOTIFICATION_SERVICE);
			final boolean sound = prefs.getBoolean("sound", false);
			final boolean vibrate = prefs.getBoolean("vibrate", false);
			final boolean lights = prefs.getBoolean("lights", false);
			
			for (final Tweet t : tweets) {
				final Notification n = new Notification();
				n.icon = R.drawable.icon; // TODO proper notification icon
				final String screenName = t.getScreenName();
				Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(
					twitterRoot + URLEncoder.encode(screenName) +
					"/status/" + t.getID()));
				i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				n.contentIntent = PendingIntent.getActivity(
					Fetcher.this, 0, i, 0);
				
				final String text = t.getText();
				final RemoteViews v = new RemoteViews(
					getPackageName(), (text.length() > 95) ?
					R.layout.notification_longtext : R.layout.notification);
				final Date d = t.getDate();
				v.setTextViewText(R.id.notification_time, df.format(d));
				v.setTextViewText(R.id.notification_user, screenName);
				v.setTextViewText(R.id.notification_text, text);
				
				n.contentView = v;
				n.when = d.getTime();
				n.defaults =
					(sound ? Notification.DEFAULT_SOUND : 0) |
					(vibrate ? Notification.DEFAULT_VIBRATE : 0);
				if (lights) {
					n.flags |= Notification.FLAG_SHOW_LIGHTS;
					n.ledOnMS = 1000;
					n.ledOffMS = 2000;
					n.ledARGB = 0xFF00007F;
				}
				n.flags |= Notification.FLAG_AUTO_CANCEL;
				
				nm.notify((int) (t.getID() % Integer.MAX_VALUE), n);
			}
			
			{
				OutputStreamWriter osw = null;
				try {
					osw = new OutputStreamWriter(
						openFileOutput(LAST_TWEET_ID_FILENAME, 0),
						FILE_CHARSET);
					osw.write(
						Long.toString(lastFriendStatus) + "\n" +
						Long.toString(lastMessage) + "\n" +
						Long.toString(lastReply) + "\n");
				} catch (IOException e) {
					// This is a fairly big problem, because we'll keep
					// notifying the user about the same tweets, but I don't
					// think that there's anything sensible we can do about it.
					// An IOException here probably indicates a critical lack of
					// filesystem space.
					Log.e(LOG_TAG, "Couldn't write last tweet ID.");
				} finally {
					if (osw != null) {
						try {
							osw.close();
						} catch (IOException e) {
						}
					}
				}
			}
		}
	
		@Override
		public void run() {
			try {
				fetch();
			} catch (DownloadException e) {
				Log.v(LOG_TAG, "A download failed.");
			} finally {
				// This is in a finally because if stopIfIdle() isn't called
				// then the wake lock would be held forever. That would suck.
				inProgress = false;
				handler.post(new Runnable() {
					@Override
					public void run() {
						stopIfIdle();
					}
				});
			}
		}
	}

	private void stopIfIdle() {
		if (fetcherThread != null && fetcherThread.inProgress()) {
			// This could happen if this call to stopIfIdle() gets queued after
			// another starting of the service (which would fire up another
			// thread).
			Log.d(LOG_TAG, "Not stopping service because thread is running.");
			return;
		}
		wakeLock.release();
		Log.d(LOG_TAG, "Stopping service.");
		stopSelf();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		
		Log.d(LOG_TAG, "Service destroyed.");
	}
}
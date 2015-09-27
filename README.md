**_This project is unmaintained. I recommend all users move to an alternative app._**

# nanoTweeter
This application is a very simple client for the social/microblogging service [Twitter](http://twitter.com/). It works on [Android](http://www.android.com/) phones.

The mobile version of the Twitter web site at [mobile.twitter.com](http://mobile.twitter.com/) does almost everything you would want, but it can't notify you about new updates as they happen, which is where nanoTweeter comes in. It runs in the background and regularly checks for new updates and messages. When you get a new @reply or direct message it notifies you using the usual Android notification system. You can also configure it to notify you about all the tweets posted by people you follow, or just the tweets from a selection of them.

The client is intentionally minimalist and doesn't attempt to duplicate anything that you can do on the mobile Twitter site, so there's no browsing and you can't post tweets using it.

## Download
nanoTweeter is now available via the Android Market (for free). If you are viewing this on your Android phone then there is a link to it's Market entry [here](http://play.google.com/store/apps/details?id=net.jjc1138.android.twitter).

## Versions
Follow [@nanoTweeter](https://twitter.com/nanoTweeter) on Twitter for notifications about new releases.

<table>
<tr><td>1.0.4</td><td>2012-05-07</td><td>Updated to work with <a href="https://dev.twitter.com/docs/deprecations/spring-2012">compatibility changes being made by Twitter</a>.</td></tr>
<tr><td>1.0.3</td><td>2011-08-10</td><td>Fixed Direct Messages not working. Thank you to Kevin Marks for the patch.</td></tr>
<tr><td>1.0.2</td><td>2011-02-17</td><td>Fixed the text being unreadable on phones that use dark backgrounds for the notification items.</td></tr>
<tr><td>1.0.1</td><td>2010-08-16</td><td>Use OAuth to sign in (because Twitter are turning off support for the old way of logging in).<br>Improved contrast of the status bar icon.<br>Make tapping on tweets go to mobile.twitter.com instead of m.twitter.com.<br>Made the whitelist/blacklist of users case-insensitive.<br>Hopefully fixed the app sometimes not activating properly when the phone is first switched on.<br>Added an option to show tweets for none of the people who you are following, and made this the default option for new users.<br>Made it so that checking for new tweets can be batched with other background apps to save battery power.<br>Added new graphics for high resolution screens and tweaked the layouts for high and low resolution.</td></tr>
<tr><td>1.0.0</td><td>2008-12-06</td><td>First release</td></tr>
</table>

## Bugs / Suggestions / Contributions
You can report a bug or suggestion in the "Issues" section. That's also the place to go if you have a patch that you'd like to contribute.

## Other comments
If you have more general feedback then please feel free to send an e-mail to: nanotweeter at jjc1138 dot net.

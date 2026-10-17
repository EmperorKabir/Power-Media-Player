import sys, datetime, email.utils, os
n = int(sys.argv[1]) if len(sys.argv) > 1 else 2
base = "http://192.168.50.31:8765"
now = datetime.datetime.now(datetime.timezone.utc)
items = []
for i in range(n, 0, -1):
    pub = email.utils.format_datetime(now - datetime.timedelta(days=(n - i)))
    items.append(f"""    <item>
      <title>PMP Test Episode {i}</title>
      <guid isPermaLink="false">pmp-test-ep-{i}</guid>
      <pubDate>{pub}</pubDate>
      <enclosure url="{base}/ep{i}.mp3" type="audio/mpeg" length="132540"/>
      <itunes:duration>00:00:22</itunes:duration>
    </item>""")
xml = f"""<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
  <channel>
    <title>PMP Local Test Feed</title>
    <description>Scenario-test feed served from the PC</description>
    <itunes:image href="{base}/cover.jpg"/>
{chr(10).join(items)}
  </channel>
</rss>
"""
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "feed.xml")
open(out, "w", encoding="utf-8", newline="\n").write(xml)
print(f"feed.xml written with {n} episodes")

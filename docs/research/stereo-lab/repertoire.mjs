// Agreed research scope: ten broad families, five preselected songs each.
// IDs stay stable across catalogue evidence and captures; no extra genre pool.
const selections=[
  ["electronic","Electronic",[
    ["house-disco-1","Daft Punk","Get Lucky","Random Access Memories"],
    ["house-disco-3","Disclosure","Latch","Settle"],
    ["house-disco-4","deadmau5","Strobe","For Lack of a Better Name"],
    ["trance-5","Tiësto","Adagio for Strings","Just Be"],
    ["dnb-bass-1","Pendulum","Watercolour","Immersion"]]],
  ["pop","Pop",[
    ["pop-1","The Weeknd","Blinding Lights","After Hours"],
    ["pop-2","Dua Lipa","Levitating","Future Nostalgia"],
    ["pop-3","Taylor Swift","Anti-Hero","Midnights"],
    ["pop-4","Ariana Grande","Into You","Dangerous Woman"],
    ["art-pop-1","Billie Eilish","bad guy","WHEN WE ALL FALL ASLEEP, WHERE DO WE GO?"]]],
  ["rock","Rock",[
    ["classic-rock-1","Queen","Another One Bites the Dust","The Game"],
    ["classic-rock-2","AC/DC","Back In Black","Back In Black"],
    ["alternative-rock-2","Arctic Monkeys","Do I Wanna Know?","AM"],
    ["alternative-rock-3","The Killers","Mr. Brightside","Hot Fuss"],
    ["alternative-rock-5","Foo Fighters","The Pretender","Echoes, Silence, Patience & Grace"]]],
  ["metal","Metal",[
    ["metal-1","Metallica","Enter Sandman","Metallica"],
    ["metal-2","Rammstein","Deutschland","Rammstein"],
    ["metal-3","Slipknot","Duality","Vol. 3: The Subliminal Verses"],
    ["metal-4","Gojira","Stranded","Magma"],
    ["metal-5","Bring Me The Horizon","Can You Feel My Heart","Sempiternal"]]],
  ["hip-hop","Hip hop",[
    ["hip-hop-1","Dr. Dre","Still D.R.E.","2001"],
    ["hip-hop-2","Nas","N.Y. State of Mind","Illmatic"],
    ["hip-hop-5","Kendrick Lamar","Alright","To Pimp a Butterfly"],
    ["trap-drill-1","Travis Scott","SICKO MODE","ASTROWORLD"],
    ["trap-drill-2","Future","Mask Off","FUTURE"]]],
  ["rnb-soul-funk","R&B / soul / funk",[
    ["rnb-1","SZA","Good Days","SOS"],
    ["rnb-2","Frank Ocean","Pink + White","Blonde"],
    ["rnb-5","D’Angelo","Untitled (How Does It Feel)","Voodoo"],
    ["soul-funk-1","Silk Sonic","Leave the Door Open","An Evening With Silk Sonic"],
    ["soul-funk-3","Stevie Wonder","Superstition","Talking Book"]]],
  ["acoustic-folk-country","Acoustic / folk / country",[
    ["folk-acoustic-1","Tracy Chapman","Fast Car","Tracy Chapman"],
    ["folk-acoustic-2","Bon Iver","Holocene","Bon Iver"],
    ["folk-acoustic-4","José González","Heartbeats","Veneer"],
    ["country-1","Chris Stapleton","Tennessee Whiskey","Traveller"],
    ["country-2","Kacey Musgraves","Slow Burn","Golden Hour"]]],
  ["jazz-blues","Jazz / blues",[
    ["jazz-1","Norah Jones","Don’t Know Why","Come Away With Me"],
    ["jazz-3","Miles Davis","So What","Kind of Blue"],
    ["jazz-4","The Dave Brubeck Quartet","Take Five","Time Out"],
    ["blues-1","B.B. King","The Thrill Is Gone","Completely Well"],
    ["blues-2","Stevie Ray Vaughan & Double Trouble","Tin Pan Alley","Couldn’t Stand the Weather"]]],
  ["orchestral-cinematic","Orchestral / symphonic / cinematic",[
    ["orchestral-1","Herbert von Karajan","Symphony No. 5 in C Minor, Op. 67: I. Allegro con brio","Beethoven: Symphonies (1963, Berliner Philharmoniker)"],
    ["orchestral-5","Gustavo Dudamel","Danzón No. 2","Fiesta (Simón Bolívar Youth Orchestra)"],
    ["cinematic-1","Hans Zimmer","Time","Inception"],
    ["cinematic-2","John Williams","Hedwig’s Theme","Harry Potter and the Sorcerer’s Stone"],
    ["cinematic-3","Max Richter","On the Nature of Daylight","The Blue Notebooks"]]],
  ["latin","Latin",[
    ["latin-pop-1","Shakira","Hips Don’t Lie","Oral Fixation, Vol. 2"],
    ["latin-pop-3","Alejandro Sanz","Corazón Partío","Más"],
    ["reggaeton-1","Bad Bunny","Tití Me Preguntó","Un Verano Sin Ti"],
    ["reggaeton-3","KAROL G","PROVENZA","MAÑANA SERÁ BONITO"],
    ["latin-organic-1","Marc Anthony","Vivir Mi Vida","3.0"]]]
];
export const corpus={
  schema:2,selectedBeforeMeasurement:true,
  scope:"50 references in ten broad families; substyles are tags, not separate UI profiles. Five observations are exploratory, not a universal norm.",
  groups:selections.map(([id,label,tracks])=>({id,label,tracks:tracks.map(([id,artist,title,intendedAlbum])=>({
    id,artist,title,intendedAlbum,sourceStatus:'unresolved',permission:null,
    masteringEditionVerified:false,eligibleForProfile:false
  }))}))
};

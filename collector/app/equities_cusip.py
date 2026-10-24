"""
app/equities_cusip.py
모의 매매 트랙레코드 후보 기관(indicators.TRACK_RECORD_FILERS)의 13F 보유에 붙이는 CUSIP → 야후 티커 매핑표.

[왜 이름이 아니라 CUSIP인가]
같은 증권을 기관마다 다른 이름으로 적습니다('FACEBOOK INC'·'META PLATFORMS, INC., CLASS A' — 둘 다 30303M102). 'ISHARES TR' 한 이름
아래에는 서로 다른 ETF가 수백 개입니다. 이름 매핑표(equities.EQUITY_MAP)로는 후보 27곳 중 1곳만 커버리지 기준을 넘었습니다(2026-10-24
단계 0). CUSIP은 증권마다 하나라(ADR 0015 결정 1) 이름 규칙보다 엄격합니다.

[어떻게 만들었나 — docs/specs/2026-10-24-paper-trading-step0.md 표 E]
후보 기관 30분기 보유 중 이름 매핑표로 덮이지 않는 지분 증권을 CUSIP별로 모아(7년 창 분기의 기관 내 비중 합) 상위 600개를
OpenFIGI v3/mapping(미국 발행사는 ID_CUSIP, 외국 발행사는 ID_CINS)의 US 합성 티커·이름, SEC company_tickers.json 등록 이름, 야후
10년 이력(첫·마지막 날짜)과 대조했습니다. 다른 증권으로 보이는 행은 없었고(검토 5회), OpenFIGI가 US 티커를 주지 않은 101개
(상장폐지·인수, 역분할·합병 전 옛 CUSIP)는 넣지 않았습니다 — 가격도 구할 수 없습니다.

[쓰는 곳]
tasks/filings.task_sec_13f_track_record가 q30 저장본에 티커를 붙일 때만(equities.attach_tickers(…, cusip_map=…)). 기존 13F 화면(q8)과
equity.price_history는 이 표를 쓰지 않습니다. 채권·우선주·워런트 배제 규칙(classify_holding)이 먼저입니다.

[고칠 때] 손으로 티커를 고치지 말고, 같은 절차로 다시 대조해 근거를 남깁니다. 표에 없는 CUSIP은 "모른다"(None)입니다.
"""
from __future__ import annotations

# CUSIP(9자) → 야후 티커. 주석은 OpenFIGI 이름·종류(검토용).
TRACK_RECORD_CUSIPS: dict[str, str] = {
    "001084102": "AGCO",  # AGCO CORP · Common Stock
    "00187Y100": "APG",  # API GROUP CORP · Common Stock
    "00404A109": "ACHC",  # ACADIA HEALTHCARE CO INC · Common Stock
    "00650F109": "ADPT",  # ADAPTIVE BIOTECHNOLOGIES · Common Stock
    "00724F101": "ADBE",  # ADOBE INC · Common Stock
    "00751Y106": "AAP",  # ADVANCE AUTO PARTS INC · Common Stock
    "00827B106": "AFRM",  # AFFIRM HOLDINGS INC · Common Stock
    "00846U101": "A",  # AGILENT TECHNOLOGIES INC · Common Stock
    "009066101": "ABNB",  # AIRBNB INC-CLASS A · Common Stock
    "009158106": "APD",  # AIR PRODUCTS & CHEMICALS INC · Common Stock
    "012653101": "ALB",  # ALBEMARLE CORP · Common Stock
    "013872106": "AA",  # ALCOA CORP · Common Stock
    "01609W102": "BABA",  # ALIBABA GROUP HOLDING-SP ADR · ADR
    "016255101": "ALGN",  # ALIGN TECHNOLOGY INC · Common Stock
    "020002101": "ALL",  # ALLSTATE CORP · Common Stock
    "02005N100": "ALLY",  # ALLY FINANCIAL INC · Common Stock
    "02043Q107": "ALNY",  # ALNYLAM PHARMACEUTICALS INC · Common Stock
    "02079K107": "GOOG",  # ALPHABET INC-CL C · Common Stock
    "02079K305": "GOOGL",  # ALPHABET INC-CL A · Common Stock
    "02156K103": "OPTU",  # OPTIMUM COMMUNICATIONS INC-A · Common Stock
    "023135106": "AMZN",  # AMAZON.COM INC · Common Stock
    "026874784": "AIG",  # AMERICAN INTERNATIONAL GROUP · Common Stock
    "03073E105": "COR",  # CENCORA INC · Common Stock
    "03076C106": "AMP",  # AMERIPRISE FINANCIAL INC · Common Stock
    "032095101": "APH",  # AMPHENOL CORP-CL A · Common Stock
    "03524A108": "BUD",  # ANHEUSER-BUSCH INBEV-SPN ADR · ADR
    "03674X106": "AR",  # ANTERO RESOURCES CORP · Common Stock
    "036752103": "ELV",  # ELEVANCE HEALTH INC · Common Stock
    "03769M106": "APO",  # APOLLO GLOBAL MANAGEMENT INC · Common Stock
    "03831W108": "APP",  # APPLOVIN CORP-CLASS A · Common Stock
    "03852U106": "ARMK",  # ARAMARK · Common Stock
    "039483102": "ADM",  # ARCHER-DANIELS-MIDLAND CO · Common Stock
    "042068205": "ARM",  # ARM HOLDINGS PLC-ADR · ADR
    "04626A103": "ALAB",  # ASTERA LABS INC · Common Stock
    "049468101": "TEAM",  # ATLASSIAN CORP-CL A · Common Stock
    "052769106": "ADSK",  # AUTODESK INC · Common Stock
    "05352A100": "AVTR",  # AVANTOR INC · Common Stock
    "055622104": "BP",  # BP PLC-SPONS ADR · ADR
    "056752108": "BIDU",  # BAIDU INC - SPON ADR · ADR
    "05722G100": "BKR",  # BAKER HUGHES CO · Common Stock
    "058498106": "BALL",  # BALL CORP · Common Stock
    "060505104": "BAC",  # BANK OF AMERICA CORP · Common Stock
    "064058100": "BNY",  # BANK OF NEW YORK MELLON CORP · Common Stock
    "070830104": "BBWI",  # BATH & BODY WORKS INC · Common Stock
    "071813109": "BAX",  # BAXTER INTERNATIONAL INC · Common Stock
    "075887109": "BDX",  # BECTON DICKINSON AND CO · Common Stock
    "07725L102": "ONC",  # BEONE MEDICINES LTD-ADR · ADR
    "084670108": "BRK-A",  # BERKSHIRE HATHAWAY INC-CL A · Common Stock
    "084670702": "BRK-B",  # BERKSHIRE HATHAWAY INC-CL B · Common Stock
    "088606108": "BHP",  # BHP GROUP LTD-SPON ADR · ADR
    "090043100": "BILL",  # BILL HOLDINGS INC · Common Stock
    "09061G101": "BMRN",  # BIOMARIN PHARMACEUTICAL INC · Common Stock
    "09073M104": "TECH",  # BIO-TECHNE CORP · Common Stock
    "09075V102": "BNTX",  # BIONTECH SE-ADR · ADR
    "09260D107": "BX",  # BLACKSTONE INC · Common Stock
    "101137107": "BSX",  # BOSTON SCIENTIFIC CORP · Common Stock
    "10806X102": "BBIO",  # BRIDGEBIO PHARMA INC · Common Stock
    "10922N103": "BHF",  # BRIGHTHOUSE FINANCIAL INC · Common Stock
    "110122108": "BMY",  # BRISTOL-MYERS SQUIBB CO · Common Stock
    "11271J107": "BN",  # BROOKFIELD CORP · Common Stock
    "113004105": "BAM",  # BROOKFIELD ASSET MGMT-A · Common Stock
    "115637100": "BF-A",  # BROWN-FORMAN CORP-CLASS A · Common Stock
    "115637209": "BF-B",  # BROWN-FORMAN CORP-CLASS B · Common Stock
    "116794108": "BRKR",  # BRUKER CORP · Common Stock
    "122017106": "BURL",  # BURLINGTON STORES INC · Common Stock
    "12504L109": "CBRE",  # CBRE GROUP INC - A · Common Stock
    "12510Q100": "CCC",  # CCC INTELLIGENT SOLUTIONS HO · Common Stock
    "125523100": "CI",  # THE CIGNA GROUP · Common Stock
    "12572Q105": "CME",  # CME GROUP INC · Common Stock
    "126408103": "CSX",  # CSX CORP · Common Stock
    "12653C108": "CNX",  # CNX RESOURCES CORP · Common Stock
    "12685J105": "CABO",  # CABLE ONE INC · Common Stock
    "127387108": "CDNS",  # CADENCE DESIGN SYS INC · Common Stock
    "12769G100": "CZR",  # CAESARS ENTERTAINMENT INC · Common Stock
    "133131102": "CPT",  # CAMDEN PROPERTY TRUST · REIT
    "134429109": "CPB",  # THE CAMPBELL'S COMPANY · Common Stock
    "136385101": "CNQ",  # CANADIAN NATURAL RESOURCES · Common Stock
    "13646K108": "CP",  # CANADIAN PACIFIC KANSAS CITY · Common Stock
    "14040H105": "COF",  # CAPITAL ONE FINANCIAL CORP · Common Stock
    "143130102": "KMX",  # CARMAX INC · Common Stock
    "14316J108": "CG",  # CARLYLE GROUP INC/THE · Common Stock
    "144285103": "CRS",  # CARPENTER TECHNOLOGY · Common Stock
    "14448C104": "CARR",  # CARRIER GLOBAL CORP · Common Stock
    "146869102": "CVNA",  # CARVANA CO · Common Stock
    "147528103": "CASY",  # CASEY'S GENERAL STORES INC · Common Stock
    "150870103": "CE",  # CELANESE CORP · Common Stock
    "15135B101": "CNC",  # CENTENE CORP · Common Stock
    "15135U109": "CVE",  # CENOVUS ENERGY INC · Common Stock
    "15675D103": "CBRS",  # CEREBRAS SYSTEMS INC - A · Common Stock
    "16119P108": "CHTR",  # CHARTER COMMUNICATIONS INC-A · Common Stock
    "163851108": "CC",  # CHEMOURS CO/THE · Common Stock
    "16411R208": "LNG",  # CHENIERE ENERGY INC · Common Stock
    "16679L109": "CHWY",  # CHEWY INC - CLASS A · Common Stock
    "169656105": "CMG",  # CHIPOTLE MEXICAN GRILL INC · Common Stock
    "184496107": "CLH",  # CLEAN HARBORS INC · Common Stock
    "18915M107": "NET",  # CLOUDFLARE INC - CLASS A · Common Stock
    "192446102": "CTSH",  # COGNIZANT TECH SOLUTIONS-A · Common Stock
    "19247G107": "COHR",  # COHERENT CORP · Common Stock
    "20602D101": "CNXC",  # CONCENTRIX CORP · Common Stock
    "21036P108": "STZ",  # CONSTELLATION BRANDS INC-A · Common Stock
    "217204106": "CPRT",  # COPART INC · Common Stock
    "21871N101": "CXW",  # CORECIVIC INC · Common Stock
    "21873S108": "CRWV",  # COREWEAVE INC-CL A · Common Stock
    "21874C102": "CNM",  # CORE & MAIN INC-CLASS A · Common Stock
    "218937100": "CNR",  # CORE NATURAL RESOURCES INC · Common Stock
    "219350105": "GLW",  # CORNING INC · Common Stock
    "219948106": "CPAY",  # CORPAY INC · Common Stock
    "22160N109": "CSGP",  # COSTAR GROUP INC · Common Stock
    "22266T109": "CPNG",  # COUPANG INC · Common Stock
    "225310101": "CACC",  # CREDIT ACCEPTANCE CORP · Common Stock
    "227046109": "CROX",  # CROCS INC · Common Stock
    "22788C105": "CRWD",  # CROWDSTRIKE HOLDINGS INC - A · Common Stock
    "228368106": "CCK",  # CROWN HOLDINGS INC · Common Stock
    "231021106": "CMI",  # CUMMINS INC · Common Stock
    "23331A109": "DHI",  # DR HORTON INC · Common Stock
    "23355L106": "DXC",  # DXC TECHNOLOGY CO · Common Stock
    "237266101": "DAR",  # DARLING INGREDIENTS INC · Common Stock
    "23804L103": "DDOG",  # DATADOG INC - CLASS A · Common Stock
    "23908L207": "DUSA",  # DAVIS SELECT US EQUITY ETF · ETP
    "23918K108": "DVA",  # DAVITA INC · Common Stock
    "244199105": "DE",  # DEERE & CO · Common Stock
    "247361702": "DAL",  # DELTA AIR LINES INC · Common Stock
    "250565108": "DBI",  # DESIGNER BRANDS INC-CLASS A · Common Stock
    "252131107": "DXCM",  # DEXCOM INC · Common Stock
    "25243Q205": "DEO",  # DIAGEO PLC-SPONSORED ADR · ADR
    "253393102": "DKS",  # DICK'S SPORTING GOODS INC · Common Stock
    "254687106": "DIS",  # WALT DISNEY CO/THE · Common Stock
    "256163106": "DOCU",  # DOCUSIGN INC · Common Stock
    "256677105": "DG",  # DOLLAR GENERAL CORP · Common Stock
    "256746108": "DLTR",  # DOLLAR TREE INC · Common Stock
    "25754A201": "DPZ",  # DOMINO'S PIZZA INC · Common Stock
    "25809K105": "DASH",  # DOORDASH INC - A · Common Stock
    "26210C104": "DBX",  # DROPBOX INC-CLASS A · Common Stock
    "268150109": "DT",  # DYNATRACE INC · Common Stock
    "26884L109": "EQT",  # EQT CORP · Common Stock
    "26969P108": "EXP",  # EAGLE MATERIALS INC · Common Stock
    "27579R104": "EWBC",  # EAST WEST BANCORP INC · Common Stock
    "278642103": "EBAY",  # EBAY INC · Common Stock
    "278768106": "ECHO",  # ECHOSTAR CORP-A · Common Stock
    "278865100": "ECL",  # ECOLAB INC · Common Stock
    "28176E108": "EW",  # EDWARDS LIFESCIENCES CORP · Common Stock
    "28414H103": "ELAN",  # ELANCO ANIMAL HEALTH INC · Common Stock
    "29273V100": "ET",  # ENERGY TRANSFER LP · MLP
    "29355A107": "ENPH",  # ENPHASE ENERGY INC · Common Stock
    "29362U104": "ENTG",  # ENTEGRIS INC · Common Stock
    "293792107": "EPD",  # ENTERPRISE PRODUCTS PARTNERS · MLP
    "29414B104": "EPAM",  # EPAM SYSTEMS INC · Common Stock
    "294429105": "EFX",  # EQUIFAX INC · Common Stock
    "29476L107": "VMRK",  # VIVMARK RESIDENTIAL · REIT
    "30212P303": "EXPE",  # EXPEDIA GROUP INC · Common Stock
    "30260D103": "FIGS",  # FIGS INC-CLASS A · Common Stock
    "30303M102": "META",  # META PLATFORMS INC-CLASS A · Common Stock
    "303075105": "FDS",  # FACTSET RESEARCH SYSTEMS INC · Common Stock
    "303250104": "FICO",  # FAIR ISAAC CORP · Common Stock
    "30744W107": "FTCHQ",  # FARFETCH LTD-CLASS A · Common Stock · 장외
    "31428X106": "FDX",  # FEDEX CORP · Common Stock
    "31488V107": "FERG",  # FERGUSON ENTERPRISES INC · Common Stock
    "31620M106": "FIS",  # FIDELITY NATIONAL INFO SERV · Common Stock
    "31620R303": "FNF",  # FIDELITY NATIONAL FINANCIAL · Common Stock
    "31946M103": "FCNCA",  # FIRST CITIZENS BCSHS  -CL A · Common Stock
    "337738108": "FISV",  # FISERV INC · Common Stock
    "339750101": "FND",  # FLOOR & DECOR HOLDINGS INC-A · Common Stock
    "343412102": "FLR",  # FLUOR CORP · Common Stock
    "34354P105": "FLS",  # FLOWSERVE CORP · Common Stock
    "34959E109": "FTNT",  # FORTINET INC · Common Stock
    "34959J108": "FTV",  # FORTIVE CORP · Common Stock
    "35137L105": "FOXA",  # FOX CORP - CLASS A · Common Stock
    "35137L204": "FOX",  # FOX CORP - CLASS B · Common Stock
    "35671D857": "FCX",  # FREEPORT-MCMORAN INC · Common Stock
    "36162J106": "GEO",  # GEO GROUP INC/THE · Common Stock
    "36165L108": "GDS",  # GDS HOLDINGS LTD - ADR · ADR
    "36168Q104": "GFL",  # GFL ENVIRONMENTAL INC-SUB VT · Common Stock
    "36266G107": "GEHC",  # GE HEALTHCARE TECHNOLOGY · Common Stock
    "363576109": "AJG",  # ARTHUR J GALLAGHER & CO · Common Stock
    "36467W109": "GME",  # GAMESTOP CORP-CLASS A · Common Stock
    "364760108": "GAP",  # GAP INC/THE · Common Stock
    "36828A101": "GEV",  # GE VERNOVA INC · Common Stock
    "369550108": "GD",  # GENERAL DYNAMICS CORP · Common Stock
    "369604301": "GE",  # GENERAL ELECTRIC · Common Stock
    "372460105": "GPC",  # GENUINE PARTS CO · Common Stock
    "37733W204": "GSK",  # GSK PLC-SPON ADR · ADR
    "37940X102": "GPN",  # GLOBAL PAYMENTS INC · Common Stock
    "382550101": "GT",  # GOODYEAR TIRE & RUBBER CO · Common Stock
    "38268T103": "GPRO",  # GOPRO INC-CLASS A · Common Stock
    "388689101": "GPK",  # GRAPHIC PACKAGING HOLDING CO · Common Stock
    "392709101": "GRBK",  # GREEN BRICK PARTNERS INC · Common Stock
    "40131M109": "GH",  # GUARDANT HEALTH INC · Common Stock
    "40412C101": "HCA",  # HCA HEALTHCARE INC · Common Stock
    "40415F101": "HDB",  # HDFC BANK LTD-ADR · ADR
    "40434L105": "HPQ",  # HP INC · Common Stock
    "405217100": "HAIN",  # HAIN CELESTIAL GROUP INC · Common Stock
    "422806208": "HEI-A",  # HEICO CORP-CLASS A · Common Stock
    "423452101": "HP",  # HELMERICH & PAYNE · Common Stock
    "42806J700": "HTZ",  # HERTZ GLOBAL HLDGS INC · Common Stock
    "43300A203": "HLT",  # HILTON WORLDWIDE HOLDINGS IN · Common Stock
    "438128308": "HMC",  # HONDA MOTOR CO LTD-SPONS ADR · ADR
    "44267T102": "HHH",  # HOWARD HUGHES HOLDINGS INC · Common Stock
    "444859102": "HUM",  # HUMANA INC · Common Stock
    "44812J104": "HUT",  # HUT 8 CORP · Common Stock
    "44891N208": "PPLI",  # PEOPLE INC · Common Stock
    "45104G104": "IBN",  # ICICI BANK LTD-SPON ADR · ADR
    "45337C102": "INCY",  # INCYTE CORP · Common Stock
    "45687V106": "IR",  # INGERSOLL-RAND INC · Common Stock
    "457030104": "IMKTA",  # INGLES MARKETS INC-CLASS A · Common Stock
    "457669307": "INSM",  # INSMED INC · Common Stock
    "45784P101": "PODD",  # INSULET CORP · Common Stock
    "45841N107": "IBKR",  # INTERACTIVE BROKERS GRO-CL A · Common Stock
    "45866F104": "ICE",  # INTERCONTINENTAL EXCHANGE IN · Common Stock
    "459506101": "IFF",  # INTL FLAVORS & FRAGRANCES · Common Stock
    "46090E103": "QQQ",  # INVESCO QQQ TRUST SERIES 1 · ETP
    "46187W107": "INVH",  # INVITATION HOMES INC · REIT
    "46266C105": "IQV",  # IQVIA HOLDINGS INC · Common Stock
    "464286400": "EWZ",  # ISHARES MSCI BRAZIL ETF · ETP
    "464286772": "EWY",  # ISHARES MSCI SOUTH KOREA ETF · ETP
    "464287184": "FXI",  # ISHARES CHINA LARGE-CAP ETF · ETP
    "464287200": "IVV",  # ISHARES CORE S&P 500 ETF · ETP
    "464287234": "EEM",  # ISHARES MSCI EMERGING MARKET · ETP
    "464287242": "LQD",  # ISHR IBX USD INVGD CB ETF-UI · ETP
    "464287432": "TLT",  # ISHARES 20+ YEAR TREASURY BD · ETP
    "464287440": "IEF",  # ISHARES 7-10 YEAR TREASURY B · ETP
    "464287655": "IWM",  # ISHARES RUSSELL 2000 ETF · ETP
    "464288414": "MUB",  # ISHARES NATIONAL MUNI BOND E · ETP
    "464288588": "MBB",  # ISHARES MBS ETF · ETP
    "464288646": "IGSB",  # ISHARES 1-5Y INV GRADE CORP · ETP
    "46428Q109": "SLV",  # ISHARES SILVER TRUST · ETP
    "46429B671": "MCHI",  # ISHARES MSCI CHINA ETF · ETP
    "46434G103": "IEMG",  # ISHARES CORE MSCI EMERGING · ETP
    "46625H100": "JPM",  # JPMORGAN CHASE & CO · Common Stock
    "466367109": "JACK",  # JACK IN THE BOX INC · Common Stock
    "46982L108": "J",  # JACOBS SOLUTIONS INC · Common Stock
    "47215P106": "JD",  # JD.COM INC-ADR · ADR
    "478160104": "JNJ",  # JOHNSON & JOHNSON · Common Stock
    "48020Q107": "JLL",  # JONES LANG LASALLE INC · Common Stock
    "48242W106": "KBR",  # KBR INC · Common Stock
    "482497104": "BEKE",  # KE HOLDINGS INC-ADR · ADR
    "48251W104": "KKR",  # KKR & CO INC · Common Stock
    "48553T106": "BZ",  # KANZHUN LTD - ADR · ADR
    "49177J102": "KVUE",  # KENVUE INC · Common Stock
    "49427F108": "KRC",  # KILROY REALTY CORP · REIT
    "49456B101": "KMI",  # KINDER MORGAN INC · Common Stock
    "500255104": "KSS",  # KOHLS CORP · Common Stock
    "500767306": "KWEB",  # KRANESH CSI CHINA INTERNET · ETP
    "501044101": "KR",  # KROGER CO · Common Stock
    "50155Q100": "KD",  # KYNDRYL HOLDINGS INC · Common Stock
    "50202M102": "LI",  # LI AUTO INC - ADR · ADR
    "50212V100": "LPLA",  # LPL FINANCIAL HOLDINGS INC · Common Stock
    "502441306": "LVMUY",  # LVMH MOET HENNESSY-UNSP ADR · ADR · 장외
    "513272104": "LW",  # LAMB WESTON HOLDINGS INC · Common Stock
    "517834107": "LVS",  # LAS VEGAS SANDS CORP · Common Stock
    "518439104": "EL",  # ESTEE LAUDER COMPANIES-CL A · Common Stock
    "526057104": "LEN",  # LENNAR CORP-A · Common Stock
    "531229755": "FWONK",  # LIBERTY MEDIA CORP-C · Common Stock
    "532457108": "LLY",  # ELI LILLY & CO · Common Stock
    "53566V106": "LINE",  # LINEAGE INC · REIT
    "538034109": "LYV",  # LIVE NATION ENTERTAINMENT IN · Common Stock
    "540424108": "L",  # LOEWS CORP · Common Stock
    "550021109": "LULU",  # LULULEMON ATHLETICA INC · Common Stock
    "55087P104": "LYFT",  # LYFT INC-A · Common Stock
    "552953101": "MGM",  # MGM RESORTS INTERNATIONAL · Common Stock
    "553498106": "MSA",  # MSA SAFETY INC · Common Stock
    "55354G100": "MSCI",  # MSCI INC · Common Stock
    "55616P104": "M",  # MACY'S INC · Common Stock
    "565394103": "CART",  # MAPLEBEAR INC · Common Stock
    "570535104": "MKL",  # MARKEL GROUP INC · Common Stock
    "57060D108": "MKTX",  # MARKETAXESS HOLDINGS INC · Common Stock
    "571903202": "MAR",  # MARRIOTT INTERNATIONAL -CL A · Common Stock
    "573284106": "MLM",  # MARTIN MARIETTA MATERIALS · Common Stock
    "573874104": "MRVL",  # MARVELL TECHNOLOGY INC · Common Stock
    "576323109": "MTZ",  # MASTEC INC · Common Stock
    "57636Q104": "MA",  # MASTERCARD INC - A · Common Stock
    "57667L107": "MTCH",  # MATCH GROUP INC · Common Stock
    "58507V107": "MDLN",  # MEDLINE INC-CL A · Common Stock
    "58733R102": "MELI",  # MERCADOLIBRE INC · Common Stock
    "58933Y105": "MRK",  # MERCK & CO. INC. · Common Stock
    "59156R108": "MET",  # METLIFE INC · Common Stock
    "592688105": "MTD",  # METTLER-TOLEDO INTERNATIONAL · Common Stock
    "594918104": "MSFT",  # MICROSOFT CORP · Common Stock
    "595017104": "MCHP",  # MICROCHIP TECHNOLOGY INC · Common Stock
    "59522J103": "MAA",  # MID-AMERICA APARTMENT COMM · REIT
    "60770K107": "MRNA",  # MODERNA INC · Common Stock
    "60855R100": "MOH",  # MOLINA HEALTHCARE INC · Common Stock
    "60871R209": "TAP",  # MOLSON COORS BEVERAGE CO - B · Common Stock
    "60937P106": "MDB",  # MONGODB INC · Common Stock
    "61174X109": "MNST",  # MONSTER BEVERAGE CORP · Common Stock
    "615369105": "MCO",  # MOODY'S CORP · Common Stock
    "61945C103": "MOS",  # MOSAIC CO/THE · Common Stock
    "62914V106": "NIO",  # NIO INC - ADR · ADR
    "629377508": "NRG",  # NRG ENERGY INC · Common Stock
    "62944T105": "NVR",  # NVR INC · Common Stock
    "632307104": "NTRA",  # NATERA INC · Common Stock
    "641069406": "NSRGY",  # NESTLE SA-SPONS ADR · ADR · 장외
    "64110W102": "NTES",  # NETEASE INC-ADR · ADR
    "647581206": "EDU",  # NEW ORIENTAL EDUCATIO-SP ADR · ADR
    "650111107": "NYT",  # NEW YORK TIMES CO-A · Common Stock
    "65249B109": "NWSA",  # NEWS CORP - CLASS A · Common Stock
    "65336K103": "NXST",  # NEXSTAR MEDIA GROUP INC · Common Stock
    "655844108": "NSC",  # NORFOLK SOUTHERN CORP · Common Stock
    "668771108": "GEN",  # GEN DIGITAL INC · Common Stock
    "66987V109": "NVS",  # NOVARTIS AG-SPONSORED ADR · ADR
    "670100205": "NVO",  # NOVO-NORDISK A/S-SPONS ADR · ADR
    "67011P100": "DNOW",  # DNOW INC · Common Stock
    "67066G104": "NVDA",  # NVIDIA CORP · Common Stock
    "67103H107": "ORLY",  # O'REILLY AUTOMOTIVE INC · Common Stock
    "674599105": "OXY",  # OCCIDENTAL PETROLEUM CORP · Common Stock
    "679295105": "OKTA",  # OKTA INC · Common Stock
    "683712103": "OPEN",  # OPENDOOR TECHNOLOGIES INC · Common Stock
    "68404L201": "OPCH",  # OPTION CARE HEALTH INC · Common Stock
    "687793109": "OSCR",  # OSCAR HEALTH INC - CLASS A · Common Stock
    "68902V107": "OTIS",  # OTIS WORLDWIDE CORP · Common Stock
    "69047Q102": "OVV",  # OVINTIV INC · Common Stock
    "690742101": "OC",  # OWENS CORNING · Common Stock
    "69331C108": "PCG",  # P G & E CORP · Common Stock
    "693475105": "PNC",  # PNC FINANCIAL SERVICES GROUP · Common Stock
    "69370C100": "PTC",  # PTC INC · Common Stock
    "69608A108": "PLTR",  # PALANTIR TECHNOLOGIES INC-A · Common Stock
    "701094104": "PH",  # PARKER HANNIFIN CORP · Common Stock
    "70614W100": "PTON",  # PELOTON INTERACTIVE INC-A · Common Stock
    "707569109": "PENN",  # PENN ENTERTAINMENT INC · Common Stock
    "71654V408": "PBR",  # PETROLEO BRASILEIRO-SPON ADR · ADR
    "718172109": "PM",  # PHILIP MORRIS INTERNATIONAL · Common Stock
    "722304102": "PDD",  # PDD HOLDINGS INC · ADR
    "72352L106": "PINS",  # PINTEREST INC- CLASS A · Common Stock
    "74144T108": "TROW",  # T ROWE PRICE GROUP INC · Common Stock
    "741623102": "PRMB",  # PRIMO BRANDS CORP · Common Stock
    "742718109": "PG",  # PROCTER & GAMBLE CO/THE · Common Stock
    "74275K108": "PCOR",  # PROCORE TECHNOLOGIES INC · Common Stock
    "74834L100": "DGX",  # QUEST DIAGNOSTICS INC · Common Stock
    "749607107": "RLI",  # RLI CORP · Common Stock
    "74967X103": "RH",  # RH · Common Stock
    "75734B100": "RDDT",  # REDDIT INC-CL A · Common Stock
    "758750103": "RRX",  # REGAL REXNORD CORP · Common Stock
    "76118Y104": "REZI",  # RESIDEO TECHNOLOGIES INC · Common Stock
    "76131D103": "QSR",  # RESTAURANT BRANDS INTERN · Common Stock
    "76680R206": "RNG",  # RINGCENTRAL INC-CLASS A · Common Stock
    "767204100": "RIO",  # RIO TINTO PLC-SPON ADR · ADR
    "76954A103": "RIVN",  # RIVIAN AUTOMOTIVE INC-A · Common Stock
    "770700102": "HOOD",  # ROBINHOOD MARKETS INC - A · Common Stock
    "771049103": "RBLX",  # ROBLOX CORP -CLASS A · Common Stock
    "77313F106": "RCKT",  # ROCKET PHARMACEUTICALS INC · Common Stock
    "773903109": "ROK",  # ROCKWELL AUTOMATION INC · Common Stock
    "77543R102": "ROKU",  # ROKU INC · Common Stock
    "775711104": "ROL",  # ROLLINS INC · Common Stock
    "776696106": "ROP",  # ROPER TECHNOLOGIES INC · Common Stock
    "780259305": "SHEL",  # SHELL PLC-ADR · ADR
    "783513203": "RYAAY",  # RYANAIR HOLDINGS PLC-SP ADR · ADR
    "78410G104": "SBAC",  # SBA COMMUNICATIONS CORP · REIT
    "78442P106": "SLM",  # SLM CORP · Common Stock
    "78462F103": "SPY",  # SS SPDR S&P 500 ETF TRUST-US · ETP
    "78463V107": "GLD",  # SPDR GOLD SHARES · ETP
    "78464A474": "SPSB",  # SS SPDR P ST C CORP ETF · ETP
    "78467J100": "SSNC",  # SS&C TECHNOLOGIES HOLDINGS · Common Stock
    "79589L106": "IOT",  # SAMSARA INC-CL A · Common Stock
    "80105N105": "SNY",  # SANOFI-ADR · ADR
    "803054204": "SAP",  # SAP SE-SPONSORED ADR · ADR
    "808513105": "SCHW",  # SCHWAB (CHARLES) CORP · Common Stock
    "81141R100": "SE",  # SEA LTD-ADR · ADR
    "81369Y506": "XLE",  # SS ENERGY SELECT SECTOR · ETP
    "81369Y605": "XLF",  # SS FINANCIAL SELECT SECTOR · ETP
    "81578P106": "SEER",  # SEER INC · Common Stock
    "81730H109": "S",  # SENTINELONE INC -CLASS A · Common Stock
    "817565104": "SCI",  # SERVICE CORP INTERNATIONAL · Common Stock
    "82452J109": "FOUR",  # SHIFT4 PAYMENTS INC-CLASS A · Common Stock
    "82509L107": "SHOP",  # SHOPIFY INC - CLASS A · Common Stock
    "83304A106": "SNAP",  # SNAP INC - A · Common Stock
    "833445109": "SNOW",  # SNOWFLAKE INC · Common Stock
    "83444M101": "SOLV",  # SOLVENTUM CORP · Common Stock
    "835699307": "SONY",  # SONY GROUP CORP - SP ADR · ADR
    "844895102": "SWX",  # SOUTHWEST GAS HOLDINGS INC · Common Stock
    "84615Q103": "SPCX",  # SPACE EXPLORATION TECHN-CL A · Common Stock
    "84920Y106": "SPWH",  # SPORTSMAN'S WAREHOUSE HOLDIN · Common Stock
    "852234103": "XYZ",  # BLOCK INC · Common Stock
    "857477103": "STT",  # STATE STREET CORP · Common Stock
    "866966104": "SUNB",  # SUNBELT RENTALS HOLDINGS INC · Common Stock
    "867224107": "SU",  # SUNCOR ENERGY INC · Common Stock
    "86722A103": "SXC",  # SUNCOKE ENERGY INC · Common Stock
    "86771W105": "RUN",  # SUNRUN INC · Common Stock
    "87162W100": "SNX",  # TD SYNNEX CORP · Common Stock
    "87165B103": "SYF",  # SYNCHRONY FINANCIAL · Common Stock
    "871829107": "SYY",  # SYSCO CORP · Common Stock
    "872590104": "TMUS",  # T-MOBILE US INC · Common Stock
    "874039100": "TSM",  # TAIWAN SEMICONDUCTOR-SP ADR · ADR
    "874054109": "TTWO",  # TAKE-TWO INTERACTIVE SOFTWRE · Common Stock
    "874080104": "TAL",  # TAL EDUCATION GROUP- ADR · ADR
    "87422Q109": "TLN",  # TALEN ENERGY CORP · Common Stock
    "87612E106": "TGT",  # TARGET CORP · Common Stock
    "878742204": "TECK",  # TECK RESOURCES LTD-CLS B · Common Stock
    "879433829": "TDS",  # TELEPHONE AND DATA SYSTEMS · Common Stock
    "88023U101": "SGI",  # SOMNIGROUP INTERNATIONAL INC · Common Stock
    "88032Q109": "TCEHY",  # TENCENT HOLDINGS LTD-UNS ADR · ADR · 장외
    "88033G407": "THC",  # TENET HEALTHCARE CORP · Common Stock
    "88034P109": "TME",  # TENCENT MUSIC ENTERTAINM-ADR · ADR
    "880770102": "TER",  # TERADYNE INC · Common Stock
    "88322Q108": "TGTX",  # TG THERAPEUTICS INC · Common Stock
    "88339J105": "TTD",  # TRADE DESK INC/THE -CLASS A · Common Stock
    "88339P101": "REAL",  # REALREAL INC/THE · Common Stock
    "888787108": "TOST",  # TOAST INC-CLASS A · Common Stock
    "892331307": "TM",  # TOYOTA MOTOR CORP -SPON ADR · ADR
    "893641100": "TDG",  # TRANSDIGM GROUP INC · Common Stock
    "89677Q107": "TCOM",  # TRIP.COM GROUP LTD-ADR · ADR
    "89832Q109": "TFC",  # TRUIST FINANCIAL CORP · Common Stock
    "90138F102": "TWLO",  # TWILIO INC - A · Common Stock
    "902494103": "TSN",  # TYSON FOODS INC-CL A · Common Stock
    "902973304": "USB",  # US BANCORP · Common Stock
    "90353T100": "UBER",  # UBER TECHNOLOGIES INC · Common Stock
    "90364P105": "PATH",  # UIPATH INC - CLASS A · Common Stock
    "910047109": "UAL",  # UNITED AIRLINES HOLDINGS INC · Common Stock
    "910710102": "ACIC",  # AMERICAN COASTAL INSURANCE C · Common Stock
    "912008109": "USFD",  # US FOODS HOLDING CORP · Common Stock
    "91332U101": "U",  # UNITY SOFTWARE INC · Common Stock
    "91680M107": "UPST",  # UPSTART HOLDINGS INC · Common Stock
    "918204108": "VFC",  # VF CORP · Common Stock
    "91912E105": "VALE",  # VALE SA-SP ADR · ADR
    "92189F106": "GDX",  # VANECK GOLD MINERS ETF · ETP
    "921943858": "VEA",  # VANGUARD FTSE DEVELOPED ETF · ETP
    "922042858": "VWO",  # VANGUARD FTSE EMERGING MARKE · ETP
    "92206C870": "VCIT",  # VANGUARD INT-TERM CORPORATE · ETP
    "922475108": "VEEV",  # VEEVA SYSTEMS INC-CLASS A · Common Stock
    "92343E102": "VRSN",  # VERISIGN INC · Common Stock
    "92345Y106": "VRSK",  # VERISK ANALYTICS INC · Common Stock
    "92537N108": "VRT",  # VERTIV HOLDINGS CO-A · Common Stock
    "92552V100": "VSAT",  # VIASAT INC · Common Stock
    "92556V106": "VTRS",  # VIATRIS INC · Common Stock
    "925652109": "VICI",  # VICI PROPERTIES INC · REIT
    "926400102": "VSXY",  # VICTORIA'S SECRET & CO · Common Stock
    "92840M102": "VST",  # VISTRA CORP · Common Stock
    "929160109": "VMC",  # VULCAN MATERIALS CO · Common Stock
    "929740108": "WAB",  # WABTEC CORP · Common Stock
    "93403J106": "WRBY",  # WARBY PARKER INC-CLASS A · Common Stock
    "942622200": "WSO",  # WATSCO INC · Common Stock
    "94419L101": "W",  # WAYFAIR INC- CLASS A · Common Stock
    "949746101": "WFC",  # WELLS FARGO & CO · Common Stock
    "95082P105": "WCC",  # WESCO INTERNATIONAL INC · Common Stock
    "958102105": "WDC",  # WESTERN DIGITAL CORP · Common Stock
    "963320106": "WHR",  # WHIRLPOOL CORP · Common Stock
    "969457100": "WMB",  # WILLIAMS COS INC · Common Stock
    "971378104": "WSC",  # WILLSCOT HOLDINGS CORP · Common Stock
    "980745103": "WWD",  # WOODWARD INC · Common Stock
    "98138H101": "WDAY",  # WORKDAY INC-CLASS A · Common Stock
    "983793100": "XPO",  # XPO INC · Common Stock
    "98422D105": "XPEV",  # XPENG INC - ADR · ADR
    "984245100": "YPF",  # YPF S.A.-SPONSORED ADR · ADR
    "98954M200": "Z",  # ZILLOW GROUP INC - C · Common Stock
    "98956P102": "ZBH",  # ZIMMER BIOMET HOLDINGS INC · Common Stock
    "98980F104": "GTM",  # ZOOMINFO TECHNOLOGIES INC · Common Stock
    "98980L101": "ZM",  # ZOOM COMMUNICATIONS INC · Common Stock
    "B639CJ108": "BUDFF",  # ANHEUSER-BUSCH INBEV SA/NV · Common Stock · 장외
    "F72027109": "PDRDF",  # PERNOD RICARD SA · Common Stock · 장외
    "G0084W101": "ADNT",  # ADIENT PLC · Common Stock
    "G0403H108": "AON",  # AON PLC-CLASS A · Common Stock
    "G0772R208": "NTB",  # BANK OF N.T. BUTTERFIELD&SON · Common Stock
    "G1151C101": "ACN",  # ACCENTURE PLC-CL A · Common Stock
    "G1890L107": "CPRI",  # CAPRI HOLDINGS LTD · Common Stock
    "G21810109": "CLVT",  # CLARIVATE PLC · Common Stock
    "G2519Y108": "BAP",  # CREDICORP LTD · Common Stock
    "G25508105": "CRH",  # CRH PLC · Common Stock
    "G29018101": "DLO",  # DLOCAL LTD · Common Stock
    "G3643J108": "FLUT",  # FLUTTER ENTERTAINMENT PLC-DI · Common Stock
    "G4124C109": "GRAB",  # GRAB HOLDINGS LTD - CL A · Common Stock
    "G4253H101": "JHX",  # JAMES HARDIE IND PLC · Common Stock
    "G4412G101": "HLF",  # HERBALIFE LTD · Common Stock
    "G4705A100": "ICLR",  # ICON PLC · Common Stock
    "G48833118": "WFRD",  # WEATHERFORD INTERNATIONAL PL · Common Stock
    "G50871105": "JAZZ",  # JAZZ PHARMACEUTICALS PLC · Common Stock
    "G51502105": "JCI",  # JOHNSON CONTROLS INTERNATION · Common Stock
    "G5509L101": "LIVN",  # LIVANOVA PLC · Common Stock
    "G61188101": "LBTYA",  # LIBERTY GLOBAL LTD-A · Common Stock
    "G61188127": "LBTYK",  # LIBERTY GLOBAL LTD-C · Common Stock
    "G6683N103": "NU",  # NU HOLDINGS LTD/CAYMAN ISL-A · Common Stock
    "G76279101": "ROIV",  # ROIVANT SCIENCES LTD · Common Stock
    "G7709Q104": "RPRX",  # ROYALTY PHARMA PLC- CL A · Common Stock
    "G7997R103": "STX",  # SEAGATE TECHNOLOGY HOLDINGS · Common Stock
    "G7997W102": "SDRL",  # SEADRILL LIMITED · Common Stock
    "G81276100": "SIG",  # SIGNET JEWELERS LTD · Common Stock
    "G8267P108": "SW",  # SMURFIT WESTROCK PLC · Common Stock · 장외
    "G85158106": "STNE",  # STONECO LTD-A · Common Stock
    "G87052109": "TEL",  # TE CONNECTIVITY PLC · Common Stock
    "G9001E128": "LILAK",  # LIBERTY LATIN AMERIC-CL C · Common Stock
    "G96629103": "WTW",  # WILLIS TOWERS WATSON PLC · Common Stock
    "G98239109": "XP",  # XP INC - CLASS A · Common Stock
    "H25662182": "CFRHF",  # CIE FINANCIERE RICHEMO-A REG · Common Stock · 장외
    "H42097107": "UBS",  # UBS GROUP AG-REG · Common Stock
    "L8681T102": "SPOT",  # SPOTIFY TECHNOLOGY SA · Common Stock
    "M98068105": "WIX",  # WIX.COM LTD · Common Stock
    "N00985106": "AER",  # AERCAP HOLDINGS NV · Common Stock
    "N07059210": "ASML",  # ASML HOLDING NV-NY REG SHS · NY Reg Shrs
    "N14506104": "ESTC",  # ELASTIC NV · Common Stock
    "N20944109": "CNH",  # CNH INDUSTRIAL NV · Common Stock
    "N3167Y103": "RACE",  # FERRARI NV · Common Stock
    "N39338194": "HKHHF",  # HEINEKEN HOLDING NV · Common Stock · 장외
    "N53745100": "LYB",  # LYONDELLBASELL INDU-CL A · Common Stock
    "N62509109": "NAMS",  # NEWAMSTERDAM PHARMA CO NV · Common Stock
    "N82405106": "STLA",  # STELLANTIS NV · Common Stock
    "P31076105": "CPA",  # COPA HOLDINGS SA-CLASS A · Common Stock
    "V7780T103": "RCL",  # ROYAL CARIBBEAN CRUISES LTD · Common Stock
    "Y2065G121": "DHT",  # DHT HOLDINGS INC · Common Stock
    "Y2573F102": "FLEX",  # FLEX LTD · Common Stock
    "Y7542C130": "STNG",  # SCORPIO TANKERS INC · Common Stock
    "Y8162K204": "SBLK",  # STAR BULK CARRIERS CORP · Common Stock
}

#!/usr/bin/env python3
"""Analyze actual XVF position lifecycles using delayed UTC00 trade-price proxies."""
import csv,math,statistics
from collections import defaultdict
from pathlib import Path

SCRIPT_DIR=Path(__file__).resolve().parent
GENERATED_DIR=SCRIPT_DIR/'generated'
N=112.5
MAKER={'binance':1.8,'bybit':3.6,'hyperliquid':1.8}
TAKER={'binance':4.5,'bybit':10.0,'hyperliquid':4.5}
E=list(csv.DictReader((GENERATED_DIR/'xvf_basis_selected_entries.csv').open()))
M={x['entry_id']:x for x in E}
L=defaultdict(dict)
for x in csv.DictReader((GENERATED_DIR/'xvf_basis_lifecycle_legs.csv').open()): L[x['entry_id']][x['side']]=x

def factor(s):
    for p,n in [('1000000',1e6),('100000',1e5),('10000',1e4),('1000',1e3)]:
        if s.startswith(p): return n
    if s.startswith('1M') and len(s)>2 and s[2].isalpha(): return 1e6
    if s.startswith('k') and len(s)>1 and s[1].isupper(): return 1e3
    return 1
def corr(a,b):
    ma,mb=statistics.mean(a),statistics.mean(b)
    return sum((x-ma)*(y-mb) for x,y in zip(a,b))/math.sqrt(sum((x-ma)**2 for x in a)*sum((y-mb)**2 for y in b))
def quant(a,p):
    a=sorted(a); return a[round((len(a)-1)*p)]

allrows=[]; priced=[]; missing=[]
for m in E:
    s,l=L[m['entry_id']]['short'],L[m['entry_id']]['long']
    maker,taker=m['entry_maker'],m['entry_taker']
    fee=N*(MAKER[maker]+TAKER[taker]+TAKER[m['sv']]+TAKER[m['lv']])/10000
    fm=N*(float(s['funding_rate_model'])-float(l['funding_rate_model']))
    fu=N*(float(s['funding_rate_utc'])-float(l['funding_rate_utc']))
    r={'id':m['entry_id'],'period':m['period'],'day':m['entry_day'],'close':m['close_day'],
       'base':m['base'],'sv':m['sv'],'lv':m['lv'],'ss':m['sv_sym'],'ls':m['lv_sym'],
       'raw':float(m['raw_spread']),'spread':float(m['spread']),'rank':int(m['rank']),
       'funding_model':fm,'funding_utc':fu,'fee':fee}
    allrows.append(r)
    if not s['p0'] or not s['px'] or not l['p0'] or not l['px']:
        missing.append((m['entry_id'],m['entry_day'],m['close_day'],m['base'],s['venue'],s['sym'],l['venue'],l['sym']))
        continue
    ps0,psx,pl0,plx=map(float,(s['p0'],s['px'],l['p0'],l['px']))
    nps0,npsx=ps0/factor(s['sym']),psx/factor(s['sym'])
    npl0,nplx=pl0/factor(l['sym']),plx/factor(l['sym'])
    ib=math.log(nps0/npl0)*10000; xb=math.log(npsx/nplx)*10000
    br=(1-psx/ps0)+(plx/pl0-1)
    q=2*N/(nps0+npl0)
    r.update(initial_bps=ib,exit_bps=xb,conv_bps=ib-xb,basis=N*br,
             neutral_basis=q*((nps0-npsx)+(nplx-npl0)),
             combined_model=fm-fee+N*br,combined_utc=fu-fee+N*br)
    priced.append(r)

print('FUNDING/FEE RECONCILIATION ALL LIFECYCLES')
for p in sorted(set(x['period'] for x in allrows)):
    a=[x for x in allrows if x['period']==p]
    print(p,'n',len(a),'funding_model',round(sum(x['funding_model'] for x in a),2),
          'funding_utc',round(sum(x['funding_utc'] for x in a),2),'fees',round(sum(x['fee'] for x in a),2),
          'model net',round(sum(x['funding_model']-x['fee'] for x in a),2))
print('PRICE COVERAGE',len(priced),'/',len(allrows),round(len(priced)/len(allrows)*100,2),'missing',len(missing))
for x in missing: print('MISSING',x)

# H and PURR cannot be canonicalized with current instrument metadata. Keep XPL as observed and
# show a broader >10% sensitivity separately.
primary=[x for x in priced if x['base'] not in {'H','PURR'}]
sensitivity=[x for x in primary if abs(x['initial_bps'])<=1000]
def group(name,a):
    print(name,'n',len(a),'basis$',round(sum(x['basis'] for x in a),2),
          'hit%',round(100*sum(x['basis']>0 for x in a)/len(a),1),
          'median gross bp',round(statistics.median(x['basis']/225*10000 for x in a),2),
          'fund model$',round(sum(x['funding_model'] for x in a),2),
          'fund utc$',round(sum(x['funding_utc'] for x in a),2),'fee$',round(sum(x['fee'] for x in a),2),
          'combined model$',round(sum(x['combined_model'] for x in a),2),
          'combined utc$',round(sum(x['combined_utc'] for x in a),2))

print('PRIMARY (H/PURR REMOVED)')
for p in sorted(set(x['period'] for x in primary)):
    group('YEAR '+p,[x for x in primary if x['period']==p])
group('ALL',primary)
print('PRICE DISTRIBUTION $ p01,p05,p25,p50,p75,p95,p99',*[round(quant([x['basis'] for x in primary],p),3) for p in (.01,.05,.25,.5,.75,.95,.99)])
print('ENTRY BASIS DIRECTION')
for n,pred in [('aligned >5bp',lambda x:x['initial_bps']>5),('flat +/-5bp',lambda x:abs(x['initial_bps'])<=5),('adverse <-5bp',lambda x:x['initial_bps']<-5)]:
    a=[x for x in primary if pred(x)]; group(n,a)
    print(' initial median',round(statistics.median(x['initial_bps'] for x in a),2),'conv median',round(statistics.median(x['conv_bps'] for x in a),2))
print('ENTRY BASIS DIRECTION BY YEAR')
for period in sorted(set(x['period'] for x in primary)):
    for n,pred in [('aligned >5bp',lambda x:x['initial_bps']>5),('flat +/-5bp',lambda x:abs(x['initial_bps'])<=5),('adverse <-5bp',lambda x:x['initial_bps']<-5)]:
        group(period+' '+n,[x for x in primary if x['period']==period and pred(x)])
print('RAW GAP QUARTILES')
s=sorted(primary,key=lambda x:x['raw'])
for qn in range(4):
    a=s[qn*len(s)//4:(qn+1)*len(s)//4]
    group(f'Q{qn+1} [{a[0]["raw"]:.1f},{a[-1]["raw"]:.1f}]',a)
print('CORR raw/basis',corr([x['raw'] for x in primary],[x['basis'] for x in primary]),
      'raw/initial',corr([x['raw'] for x in primary],[x['initial_bps'] for x in primary]),
      'raw/convergence',corr([x['raw'] for x in primary],[x['conv_bps'] for x in primary]))
print('SENSITIVITY ALSO REMOVE >~10% ENTRY PRICE GAP')
for p in sorted(set(x['period'] for x in sensitivity)):
    group('SENS YEAR '+p,[x for x in sensitivity if x['period']==p])
group('SENS ALL',sensitivity)
print('MANUAL')
for eid in ('2024-0482','2025-0618'):
    print(next(x for x in priced if x['id']==eid))

out=GENERATED_DIR/'xvf_basis_lifecycle_results.csv'
with out.open('w',newline='') as f:
    fields=sorted(set().union(*(x.keys() for x in allrows)))+['price_covered','instrument_comparable','gap_sensitivity_ok']
    w=csv.DictWriter(f,fields); w.writeheader()
    priced_ids={x['id'] for x in priced}
    for x in allrows:
        w.writerow({**x,'price_covered':x['id'] in priced_ids,
                    'instrument_comparable':x.get('base') not in {'H','PURR'},
                    'gap_sensitivity_ok':('initial_bps' in x and abs(x['initial_bps'])<=1000)})
print('WROTE',out)

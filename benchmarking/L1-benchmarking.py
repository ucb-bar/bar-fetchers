import sys

def main():
    #TODO: make parameterizable, write bash script to run configs automatically

    with open(sys.argv[1]) as f:
        prefetch_lines = f.readlines()

    with open(sys.argv[2]) as f:
        no_prefetch_lines = f.readlines()

    misses_prevented = 0
    prefetch_queue=[]
    prefetches_sent=[]

    no_prefetch = classify_accesses(no_prefetch_lines)
    no_prefetch_misses = no_prefetch['misses']
    no_prefetch_hits = no_prefetch['hits']
    with_prefetch = classify_accesses(prefetch_lines)
    with_prefetch_hits = with_prefetch['hits']
    with_prefetch_misses = with_prefetch['misses']
    print(len(no_prefetch['hits']))
    print(len(with_prefetch_hits))
    print(len(no_prefetch_misses))
    print(len(with_prefetch_misses))

    prefetch_hits_only = list(with_prefetch_hits)
    no_prefetch_misses_only = list(no_prefetch_misses)

    for addr in no_prefetch_hits:
        if addr in prefetch_hits_only:
            prefetch_hits_only.remove(addr) #get only new hits, blind to duplicates
    for addr in with_prefetch_misses:
        if addr in no_prefetch_misses_only:
            no_prefetch_misses_only.remove(addr)
    #print(no_prefetch_misses)
    #print(with_prefetch_misses)
    #print(prefetch_hits_only)
    #print(no_prefetch_misses_only)


    # for miss in no_prefetch_misses['misses']:
    #     print('miss: ' + miss)
    # for hit in no_prefetch_misses['hits']:
    #     print('hit: ' + hit)

    useful_prefetches=[]

    for line in prefetch_lines:
        if "Prefetch Addr" in line:
            pref = line.split()
            #if len(prefetch_queue) == 1000:
            #    prefetch_queue.pop() #remove least recently added addr
            prefetches_sent.append(pref[4]) #add new prefetch address to queue
        elif "Prefetch Resp" in line:
            pref_resp = line.split()
            pref_resp_addr = pref_resp[5]
            if pref_resp_addr in prefetches_sent:
                prefetch_queue.append(pref_resp_addr)
        elif "Snoop" in line:
            snoop = line.split()
            addr = snoop[4]
            if ((addr in prefetch_queue) and (addr in no_prefetch_misses_only) and (addr in prefetch_hits_only)):
                no_prefetch_misses_only.remove(addr) # make sure miss isn't counted twice
                misses_prevented += 1
                useful_prefetches.append(addr)
    
    #Accuracy
    num_no_resp_prefetches=len(prefetches_sent)-len(prefetch_queue)
    num_unused_prefetches=len(prefetch_queue)-len(useful_prefetches)
    useless_prefetches = num_no_resp_prefetches + num_unused_prefetches
    print(num_no_resp_prefetches, num_unused_prefetches, useless_prefetches, len(useful_prefetches))

    print("misses prevented: " + str(misses_prevented))
    coverage = (misses_prevented + 0.0) / (misses_prevented + len(with_prefetch_misses)) * 100
    print("coverage: " + str(coverage) + "%")

    accuracy = (misses_prevented + 0.0) / (useless_prefetches + misses_prevented) * 100
    print("accuracy: " + str(accuracy) + "%")



def classify_accesses(lines):
    snoops = {}
    all_addr = []
    accesses = {"hits": [], "misses": []}
    last_resp_cycle = 0
    for line in lines:
        if 'Snoop' in line:
            snoop = line.split()
            snoop_cycles = snoop[1]
            addr = snoop[4]
            snoops[addr] = snoop_cycles
            all_addr.append(addr)
        elif 'Resp' in line:
            #check against snoops
            resp = line.split()
            resp_cycles = resp[1]
            resp_addr = resp[4]
            if (resp_addr in snoops): 
                if (((int(resp_cycles) - int(snoops[resp_addr])) >= 5) and (int(resp_cycles) - int(last_resp_cycle) > 3)):
                    accesses["misses"].append(resp_addr) #add snoop addr to misses
                else:
                    accesses["hits"].append(resp_addr)
                snoops.pop(resp_addr)
                last_resp_cycle = resp[1]
    #print(all_addr)
    return accesses

main()
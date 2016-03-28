package org.hypergraphdb.storage.bje;

import org.hypergraphdb.HGException;
import org.hypergraphdb.HGRandomAccessResult;
import org.hypergraphdb.storage.HGIndexStats;
import org.hypergraphdb.util.Ref;

import com.sleepycat.je.BtreeStats;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.SecondaryCursor;

public class BJEIndexStats<Key, Value> implements HGIndexStats<Key, Value>
{
	DefaultIndexImpl<Key, Value> index;
	
	public BJEIndexStats(DefaultIndexImpl<Key, Value> index)
	{
		this.index = index;
	}

	public Count entries(long cost, boolean isEstimateOk)
	{
		index.checkOpen();
		return new Count(() -> ((BtreeStats) index.db.getStats(null)).getLeafNodeCount(), false);
	}
	
	public Count keys(long cost, boolean isEstimateOk)
	{
		index.checkOpen();
		if (cost < Long.MAX_VALUE && !isEstimateOk)
			return null;
		else if (cost == Long.MAX_VALUE)
			return new Count( () -> {
				final long [] value = new long[1];
				try (HGRandomAccessResult<Key> keys = index.scanKeys())
				{
					keys.forEachRemaining(k -> value[0]++);
				}
				return value[0];
			}, false);
		else // isEstimateOk
		{
			return new Count(() -> ((BtreeStats) index.db.getStats(null)).getLeafNodeCount(), true);
		}
	}

	public Count values(long cost, boolean isEstimateOk)
	{
		index.checkOpen();
		if (cost < Long.MAX_VALUE && !isEstimateOk)
			return null;
		else if (cost == Long.MAX_VALUE)
			return new Count( () -> {
				final long [] cnt = new long[1];
				try (HGRandomAccessResult<Value> values = index.scanValues())
				{
					values.forEachRemaining(k -> cnt[0]++);
				}
				return cnt[0];
			}, false);
		else // isEstimateOk
		{
			return new Count(() -> ((BtreeStats) index.db.getStats(null)).getLeafNodeCount(), true);
		}		
	}

	public Count valuesOfKey(Key key, long cost, boolean isEstimateOk)
	{
		index.checkOpen();
		if (cost == 0)
			return null;
		else
		{
			Ref<Long> counter = () -> {
				try (Cursor cursor = index.db.openCursor(index.txn().getBJETransaction(), index.cursorConfig)) 
				{
					DatabaseEntry keyEntry = new DatabaseEntry(index.keyConverter.toByteArray(key));
					DatabaseEntry value = new DatabaseEntry();
					OperationStatus status = cursor.getSearchKey(keyEntry, value, LockMode.DEFAULT);

					if (status == OperationStatus.SUCCESS)
						return (long)cursor.count();
					else
						return 0l;
				}
				catch (DatabaseException ex)
				{
					throw new HGException(ex);
				}
			};
			return new Count(counter, false);
		}		
	}

	@Override
	public Count keysWithValue(Value value, long cost, boolean isEstimateOk)
	{
		index.checkOpen();
		if (index instanceof DefaultBiIndexImpl)
			return null;
		if (cost == 0)
			return null;
		else
		{
			DefaultBiIndexImpl<Key, Value> bindex = (DefaultBiIndexImpl<Key, Value>)index;
			Ref<Long> counter = () -> {
				try (SecondaryCursor cursor = bindex.secondaryDb.openCursor(index.txn().getBJETransaction(), index.cursorConfig)) 
				{
					DatabaseEntry keyEntry = new DatabaseEntry(bindex.valueConverter.toByteArray(value));
					DatabaseEntry valueEntry = new DatabaseEntry();
					OperationStatus status = cursor.getSearchKey(keyEntry, valueEntry, bindex.dummy, LockMode.DEFAULT);
					if (status == OperationStatus.SUCCESS)
						return (long)cursor.count();
					else
						return 0l;
				}
				catch (DatabaseException ex)
				{
					throw new HGException(ex);
				}
			};
			return new Count(counter, false);
		}		
		
	}
}
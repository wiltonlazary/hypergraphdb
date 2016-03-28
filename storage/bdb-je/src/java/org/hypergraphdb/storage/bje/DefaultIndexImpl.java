/*
 * This file is part of the HyperGraphDB source distribution. This is copyrighted software. For permitted
 * uses, licensing options and redistribution, please see the LicensingInformation file at the root level of
 * the distribution.
 * 
 * Copyright (c) 2005-2010 Kobrix Software, Inc. All rights reserved.
 */
package org.hypergraphdb.storage.bje;

import java.util.Comparator;
import java.util.function.Supplier;

import org.hypergraphdb.HGException;
import org.hypergraphdb.HGRandomAccessResult;
import org.hypergraphdb.HGSearchResult;
import org.hypergraphdb.HGSortIndex;
import org.hypergraphdb.TwoWayIterator;
import org.hypergraphdb.storage.ByteArrayConverter;
import org.hypergraphdb.storage.HGIndexStats;
import org.hypergraphdb.storage.SearchResultWrapper;
import org.hypergraphdb.transaction.HGTransaction;
import org.hypergraphdb.transaction.HGTransactionManager;
import org.hypergraphdb.transaction.VanillaTransaction;

import com.sleepycat.je.Cursor;
import com.sleepycat.je.CursorConfig;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.Transaction;

/**
 * <p>
 * A default index implementation. This implementation works by maintaining a
 * separate DB, using a B-tree, <code>byte []</code> lexicographical ordering on
 * its keys. The keys are therefore assumed to by <code>byte [] </code>
 * instances.
 * </p>
 * 
 * @author Borislav Iordanov
 */
@SuppressWarnings("unchecked")
public class DefaultIndexImpl<KeyType, ValueType> implements HGSortIndex<KeyType, ValueType>
{
	/**
	 * Prefix of HyperGraph index DB filenames.
	 */
	public static final String DB_NAME_PREFIX = "hgstore_idx_";

	protected BJEStorageImplementation storage;
	protected CursorConfig cursorConfig = new CursorConfig();
	protected HGTransactionManager transactionManager;
	protected String name;
	public Database db;
	private boolean owndb;
	protected Comparator<byte[]> keyComparator;
	protected Comparator<byte[]> valueComparator;
	protected boolean sort_duplicates = true;
	protected ByteArrayConverter<KeyType> keyConverter;
	protected ByteArrayConverter<ValueType> valueConverter;

	protected void checkOpen()
	{
		if (!isOpen())
			throw new HGException("Attempting to operate on index '" + name + "' while the index is being closed.");
	}

	protected TransactionBJEImpl txn()
	{
		HGTransaction tx = transactionManager.getContext().getCurrent();
		if (tx == null || tx.getStorageTransaction() instanceof VanillaTransaction)
			return TransactionBJEImpl.nullTransaction();
		else
			return (TransactionBJEImpl) tx.getStorageTransaction();
	}

	// public DefaultIndexImpl(Environment env,
	// Database db,
	// HGTransactionManager transactionManager,
	// ByteArrayConverter<KeyType> keyConverter,
	// ByteArrayConverter<ValueType> valueConverter,
	// Comparator comparator)
	// {
	// this.db = db;
	// this.env = env;
	// this.transactionManager = transactionManager;
	// this.keyConverter = keyConverter;
	// this.valueConverter = valueConverter;
	// this.comparator = comparator;
	// owndb = false;
	// try { name = db.getDatabaseName(); }
	// catch (Exception ex) { throw new HGException(ex); }
	//
	// }

	public DefaultIndexImpl(String indexName, 
							BJEStorageImplementation storage, 
							HGTransactionManager transactionManager,
							ByteArrayConverter<KeyType> keyConverter, 
							ByteArrayConverter<ValueType> valueConverter, 
							Comparator<byte[]> keyComparator,
							Comparator<byte[]> valueComparator)
	{
		this.name = indexName;
		this.storage = storage;
		this.transactionManager = transactionManager;
		this.keyConverter = keyConverter;
		this.valueConverter = valueConverter;
		this.keyComparator = keyComparator;
		this.valueComparator = valueComparator;
		owndb = true;
	}

	public String getName()
	{
		return name;
	}

	public String getDatabaseName()
	{
		return DB_NAME_PREFIX + name;
	}

	public ByteArrayConverter<KeyType> getKeyConverter()
	{
		return keyConverter;
	}
	
	public ByteArrayConverter<ValueType> getValueConverter()
	{
		return valueConverter;
	}
	
	public Comparator<byte[]> getKeyComparator()
	{
		try
		{
			if (keyComparator != null)
			{
				return keyComparator;
			}
			else
			{
				Comparator<byte[]> comparator = db.getConfig().getBtreeComparator();
				if (comparator == null)
					comparator = new Comparator<byte[]>() 
					{
						public int compare(byte[]left, byte[]right)
						{
							return db.compareKeys(new DatabaseEntry(left), new DatabaseEntry(right));
						}
					};
				return comparator;
			}
		}
		catch (DatabaseException ex)
		{
			throw new HGException(ex);
		}
	}

	public Comparator<byte[]> getValueComparator()
	{
		try
		{
			if (valueComparator != null)
			{
				return valueComparator;
			}
			else
			{
				return db.getConfig().getDuplicateComparator();
			}
		}
		catch (DatabaseException ex)
		{
			throw new HGException(ex);
		}
	}
	
	
	public void open()
	{
		try
		{
			DatabaseConfig dbConfig = storage.getConfiguration().getDatabaseConfig().clone();
			dbConfig.setSortedDuplicates(sort_duplicates);

			if (keyComparator != null)
			{
				dbConfig.setBtreeComparator((Comparator<byte[]>) keyComparator);
			}

			if (valueComparator != null)
			{
				dbConfig.setDuplicateComparator((Comparator<byte[]>) keyComparator);
			}
			
			db = storage.getBerkleyEnvironment().openDatabase(null, DB_NAME_PREFIX + name, dbConfig);
		}
		catch (Throwable t)
		{
			throw new HGException("While attempting to open index ;" + name + "': " + t.toString(), t);
		}
	}

	public void close()
	{
		if (db == null || !owndb)
			return;
		try
		{
			db.close();
		}
		catch (Throwable t)
		{
			throw new HGException(t);
		}
		finally
		{
			db = null;
		}
	}

	public boolean isOpen()
	{
		return db != null;
	}

	public HGRandomAccessResult<ValueType> scanValues()
	{
		checkOpen();
		DatabaseEntry keyEntry = new DatabaseEntry();
		DatabaseEntry value = new DatabaseEntry();
		HGRandomAccessResult<ValueType> result = null;
		Cursor cursor = null;

		try
		{
			TransactionBJEImpl tx = txn();
			cursor = db.openCursor(tx.getBJETransaction(), cursorConfig);
			OperationStatus status = cursor.getFirst(keyEntry, value, LockMode.DEFAULT);

			if (status == OperationStatus.SUCCESS /* && cursor.count() > 0 */)
				result = new KeyRangeForwardResultSet<ValueType>(tx.attachCursor(cursor), keyEntry, valueConverter);
			else
			{
				try
				{
					cursor.close();
				}
				catch (Throwable t)
				{
				}
				result = (HGRandomAccessResult<ValueType>) HGSearchResult.EMPTY;
			}
		}
		catch (Throwable ex)
		{
			if (cursor != null)
			{
				try
				{
					cursor.close();
				}
				catch (Throwable t)
				{
				}
			}
			throw new HGException("Failed to lookup index '" + name + "': " + ex.toString(), ex);
		}
		return result;
	}

	public HGRandomAccessResult<KeyType> scanKeys()
	{
		checkOpen();
		DatabaseEntry keyEntry = new DatabaseEntry();
		DatabaseEntry value = new DatabaseEntry();
		HGRandomAccessResult<KeyType> result = null;
		Cursor cursor = null;

		try
		{
			TransactionBJEImpl tx = txn();
			cursor = db.openCursor(tx.getBJETransaction(), cursorConfig);
			OperationStatus status = cursor.getFirst(keyEntry, value, LockMode.DEFAULT);

			if (status == OperationStatus.SUCCESS /* && cursor.count() > 0 */)
				result = new KeyScanResultSet<KeyType>(tx.attachCursor(cursor), keyEntry, keyConverter);
			else
			{
				try
				{
					cursor.close();
				}
				catch (Throwable t)
				{
				}
				result = (HGRandomAccessResult<KeyType>) HGSearchResult.EMPTY;
			}
		}
		catch (Throwable ex)
		{
			if (cursor != null)
			{
				try
				{
					cursor.close();
				}
				catch (Throwable t)
				{
				}
			}
			throw new HGException("Failed to lookup index '" + name + "': " + ex.toString(), ex);
		}
		return result;
	}

	public void addEntry(KeyType key, ValueType value)
	{
		checkOpen();
		DatabaseEntry dbkey = new DatabaseEntry(keyConverter.toByteArray(key));
		DatabaseEntry dbvalue = new DatabaseEntry(valueConverter.toByteArray(value));

		try
		{
			OperationStatus result = db.putNoDupData(txn().getBJETransaction(), dbkey, dbvalue);
			if (result != OperationStatus.SUCCESS && result != OperationStatus.KEYEXIST)
				throw new Exception("OperationStatus: " + result);
		}
		catch (Exception ex)
		{
			throw new HGException("Failed to add entry to index '" + name + "': " + ex.toString(), ex);
		}
	}

	@Override
	public void removeEntry(KeyType key, ValueType value)
	{
		checkOpen();
		DatabaseEntry keyEntry = new DatabaseEntry(keyConverter.toByteArray(key));
		DatabaseEntry valueEntry = new DatabaseEntry(valueConverter.toByteArray(value));
		Cursor cursor = null;

		try
		{
			cursor = db.openCursor(txn().getBJETransaction(), cursorConfig);
			if (cursor.getSearchBoth(keyEntry, valueEntry, LockMode.DEFAULT) == OperationStatus.SUCCESS)
				cursor.delete();
		}
		catch (Exception ex)
		{
			throw new HGException("Failed to lookup index '" + name + "': " + ex.toString(), ex);
		}
		finally
		{
			if (cursor != null)
			{
				try
				{
					cursor.close();
				}
				catch (Throwable t)
				{
				}
			}
		}
	}

	public void removeAllEntries(KeyType key)
	{
		checkOpen();
		DatabaseEntry dbkey = new DatabaseEntry(keyConverter.toByteArray(key));

		try
		{
			db.delete(txn().getBJETransaction(), dbkey);
		}
		catch (Exception ex)
		{
			throw new HGException("Failed to delete entry from index '" + name + "': " + ex.toString(), ex);
		}
	}

	void ping(Transaction tx)
	{
		DatabaseEntry key = new DatabaseEntry(new byte[1]);
		DatabaseEntry data = new DatabaseEntry();

		try
		{
			db.get(tx, key, data, LockMode.DEFAULT);
		}
		catch (Exception ex)
		{
			throw new HGException("Failed to ping index '" + name + "': " + ex.toString(), ex);
		}
	}

	public ValueType getData(KeyType key)
	{
		checkOpen();
		DatabaseEntry keyEntry = new DatabaseEntry(keyConverter.toByteArray(key));
		DatabaseEntry value = new DatabaseEntry();
		ValueType result = null;

		try
		{
			OperationStatus status = db.get(txn().getBJETransaction(), keyEntry, value, LockMode.DEFAULT);
			if (status == OperationStatus.SUCCESS)
				result = valueConverter.fromByteArray(value.getData(), value.getOffset(), value.getSize());
		}
		catch (Exception ex)
		{
			throw new HGException("Failed to lookup index '" + name + "': " + ex.toString(), ex);
		}
		return result;
	}

	public ValueType findFirst(KeyType key)
	{
		checkOpen();
		DatabaseEntry keyEntry = new DatabaseEntry(keyConverter.toByteArray(key));
		DatabaseEntry value = new DatabaseEntry();
		ValueType result = null;
		Cursor cursor = null;

		try
		{
			cursor = db.openCursor(txn().getBJETransaction(), cursorConfig);
			OperationStatus status = cursor.getSearchKey(keyEntry, value, LockMode.DEFAULT);
			if (status == OperationStatus.SUCCESS)
				result = valueConverter.fromByteArray(value.getData(), value.getOffset(), value.getSize());
		}
		catch (Exception ex)
		{
			throw new HGException("Failed to lookup index '" + name + "': " + ex.toString(), ex);
		}
		finally
		{
			if (cursor != null)
			{
				try
				{
					cursor.close();
				}
				catch (Throwable t)
				{
				}
			}
		}
		return result;
	}

	/**
	 * <p>
	 * Find the last entry, assuming ordered duplicates, corresponding to the
	 * given key.
	 * </p>
	 * 
	 * @param key
	 *            The key whose last entry is sought.
	 * @return The last (i.e. greatest, i.e. maximum) data value for that key or
	 *         null if the set of entries for the key is empty.
	 */
	public ValueType findLast(KeyType key)
	{
		checkOpen();
		DatabaseEntry keyEntry = new DatabaseEntry(keyConverter.toByteArray(key));
		DatabaseEntry value = new DatabaseEntry();
		ValueType result = null;
		Cursor cursor = null;
		try
		{
			cursor = db.openCursor(txn().getBJETransaction(), cursorConfig);
			OperationStatus status = cursor.getLast(keyEntry, value, LockMode.DEFAULT);
			if (status == OperationStatus.SUCCESS)
				result = valueConverter.fromByteArray(value.getData(), value.getOffset(), value.getSize());
		}
		catch (Exception ex)
		{
			throw new HGException("Failed to lookup index '" + name + "': " + ex.toString(), ex);
		}
		finally
		{
			if (cursor != null)
			{
				try
				{
					cursor.close();
				}
				catch (Throwable t)
				{
				}
			}
		}
		return result;
	}

	public HGRandomAccessResult<ValueType> find(KeyType key)
	{
		checkOpen();
		DatabaseEntry keyEntry = new DatabaseEntry(keyConverter.toByteArray(key));
		DatabaseEntry value = new DatabaseEntry();
		HGRandomAccessResult<ValueType> result = null;
		Cursor cursor = null;

		try
		{
			TransactionBJEImpl tx = txn();
			cursor = db.openCursor(txn().getBJETransaction(), cursorConfig);
			OperationStatus status = cursor.getSearchKey(keyEntry, value, LockMode.DEFAULT);

			if (status == OperationStatus.SUCCESS /* && cursor.count() > 0 */)
			{
				result = new SingleKeyResultSet<ValueType>(tx.attachCursor(cursor), keyEntry, valueConverter);
			}
			else
			{
				try
				{
					cursor.close();
				}
				catch (Throwable t)
				{
				}
				result = (HGRandomAccessResult<ValueType>) HGSearchResult.EMPTY;
			}
		}
		catch (Throwable ex)
		{
			if (cursor != null)
			{
				try
				{
					cursor.close();
				}
				catch (Throwable t)
				{
				}
			}
			throw new HGException("Failed to lookup index '" + name + "': " + ex.toString(), ex);
		}
		return result;
	}

	/**
	 * This is implementing the LT, LTE, GT, GTE methods.
	 * 
	 * @param key the key
	 * @param lower_range do we want key/value below the key or above
	 * @param compare_equals do we want to include values the key as well or only < or >
	 * @return
	 */
	private HGSearchResult<ValueType> findOrdered(KeyType key, boolean lower_range, boolean compare_equals)
	{
		checkOpen();
		/*
		 * if (key == null) throw new HGException("Attempting to lookup index '"
		 * + name + "' with a null key.");
		 */
		byte[] keyAsBytes = keyConverter.toByteArray(key);
		DatabaseEntry keyEntry = new DatabaseEntry(keyAsBytes);
		DatabaseEntry value = new DatabaseEntry();
		Cursor cursor = null;
		
		try
		{
			TransactionBJEImpl tx = txn();
			cursor = db.openCursor(txn().getBJETransaction(), cursorConfig);
			OperationStatus status = cursor.getSearchKeyRange(keyEntry, value, LockMode.DEFAULT);
			Supplier<ValueType> forward = null, backward = null;
			
			if (status == OperationStatus.SUCCESS)
			{
				if (!lower_range)
				{
					if (!compare_equals && getKeyComparator().compare(keyAsBytes,  keyEntry.getData()) == 0)
						status = cursor.getNextNoDup(keyEntry, value, LockMode.DEFAULT);
					if (status == OperationStatus.SUCCESS)
					{
						forward = bje.nextDataSupplier(cursor, null, valueConverter);
						backward = bje.chain(bje.prevDataSupplier(cursor, new DatabaseEntry(keyAsBytes), valueConverter),
											 bje.prevDupSupplier(cursor, new DatabaseEntry(keyAsBytes), valueConverter));
					}
				}
				else
				{
					if ( (!compare_equals || getKeyComparator().compare(keyAsBytes,  keyEntry.getData()) != 0)
						 && ((status = cursor.getPrev(keyEntry, value, LockMode.DEFAULT)) == OperationStatus.SUCCESS))
					{
						forward = bje.prevDataSupplier(cursor, null, valueConverter);
						backward = bje.nextDataSupplier(cursor, new DatabaseEntry(keyAsBytes), valueConverter);
					}
					else if (status == OperationStatus.SUCCESS)
					{
						TwoWayIterator<ValueType> specialCaseIterator = bje.lowerRangeOnKeyResultSet(cursor, keyEntry, valueConverter);
						forward = specialCaseIterator::next;
						backward = specialCaseIterator::prev;						
					}
				}
			}
			else if (lower_range && cursor.getLast(keyEntry, value, LockMode.DEFAULT) == OperationStatus.SUCCESS)
			{
				forward = bje.prevDataSupplier(cursor, null, valueConverter);
				backward = bje.nextDataSupplier(cursor, null, valueConverter);				
			}
			if (forward != null)
				return new SearchResultWrapper<ValueType>(new IndexResultSet<ValueType>(
						tx.attachCursor(cursor),
						keyEntry, 
						valueConverter,
						forward,
						backward,
						false));  
			try { cursor.close(); } catch (Throwable t) { }
			return (HGSearchResult<ValueType>) HGSearchResult.EMPTY;
		}
		catch (Throwable ex)
		{
			if (cursor != null)
			{
				try
				{
					cursor.close();
				}
				catch (Throwable t)
				{
				}
			}
			throw new HGException("Failed to lookup index '" + name + "': " + ex.toString(), ex);
		}
	}

	public HGSearchResult<ValueType> findGT(KeyType key)
	{
		return findOrdered(key, false, false);
	}

	public HGSearchResult<ValueType> findGTE(KeyType key)
	{
		return findOrdered(key, false, true);
	}

	public HGSearchResult<ValueType> findLT(KeyType key)
	{
		return findOrdered(key, true, false);
	}

	public HGSearchResult<ValueType> findLTE(KeyType key)
	{
		return findOrdered(key, true, true);
	}

	protected void finalize()
	{
		if (isOpen())
			try
			{
				close();
			}
			catch (Throwable t)
			{
			}
	}

	public long count()
	{
		//return stats().keys(Long.MAX_VALUE, false).value();
		return stats().keys(Long.MAX_VALUE, false).value();
	}

	public long count(KeyType key)
	{
//		Cursor cursor = null;
//		try
//		{
//			cursor = db.openCursor(txn().getBJETransaction(), cursorConfig);
//			DatabaseEntry keyEntry = new DatabaseEntry(keyConverter.toByteArray(key));
//			DatabaseEntry value = new DatabaseEntry();
//			OperationStatus status = cursor.getSearchKey(keyEntry, value, LockMode.DEFAULT);
//
//			if (status == OperationStatus.SUCCESS)
//				return cursor.count();
//			else
//				return 0;
//		}
//		catch (DatabaseException ex)
//		{
//			throw new HGException(ex);
//		}
//		finally
//		{
//			if (cursor != null)
//			{
//				try
//				{
//					cursor.close();
//				}
//				catch (Throwable t)
//				{
//				}
//			}
//		}
		return stats().valuesOfKey(key, Long.MAX_VALUE, false).value();
	}

	public HGIndexStats<KeyType, ValueType> stats()
	{
		return new BJEIndexStats<KeyType, ValueType>(this);
	}
}
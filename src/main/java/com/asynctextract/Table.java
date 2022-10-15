package com.asynctextract;

import java.util.List;
import java.util.Map;

import com.amazonaws.services.textract.model.Block;

public class Table {
	Block tableBlock;
	List<Block> cellBlocks;
	List<Block> cellWords;
	Map<String, Integer> coulumnRowscount;
	Map<Integer, String> columnDef;
	List<Map<Integer, String>> columnNamesList;
	Map<String, String> rowData;
	List<Map<String, String>> tableData;

	public Table() {
		super();

	}

	public Block getTableBlock() {
		return tableBlock;
	}

	public void setTableBlock(Block tableBlock) {
		this.tableBlock = tableBlock;
	}

	public List<Block> getCellBlocks() {
		return cellBlocks;
	}

	public void setCellBlocks(List<Block> cellBlocks) {
		this.cellBlocks = cellBlocks;
	}

	public List<Block> getCellWords() {
		return cellWords;
	}

	public void setCellWords(List<Block> cellWords) {
		this.cellWords = cellWords;
	}

	public Map<String, Integer> getCoulumnRowscount() {
		return coulumnRowscount;
	}

	public void setCoulumnRowscount(Map<String, Integer> coulumnRowscount) {
		this.coulumnRowscount = coulumnRowscount;
	}

	public Map<Integer, String> getColumnDef() {
		return columnDef;
	}

	public void setColumnDef(Map<Integer, String> columnDef) {
		this.columnDef = columnDef;
	}

	public List<Map<Integer, String>> getColumnNamesList() {
		return columnNamesList;
	}

	public void setColumnNamesList(List<Map<Integer, String>> columnNamesList) {
		this.columnNamesList = columnNamesList;
	}

	public Map<String, String> getRowData() {
		return rowData;
	}

	public void setRowData(Map<String, String> rowData) {
		this.rowData = rowData;
	}

	public List<Map<String, String>> getTableData() {
		return tableData;
	}

	public void setTableData(List<Map<String, String>> tableData) {
		this.tableData = tableData;
	}

}
